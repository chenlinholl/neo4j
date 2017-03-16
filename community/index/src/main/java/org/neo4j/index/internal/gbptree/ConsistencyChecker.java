/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.internal.gbptree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.io.pagecache.CursorException;
import org.neo4j.io.pagecache.PageCursor;

import static java.lang.Math.toIntExact;
import static java.lang.String.format;

import static org.neo4j.index.internal.gbptree.GenSafePointerPair.pointer;
import static org.neo4j.index.internal.gbptree.PageCursorUtil.checkOutOfBounds;

/**
 * <ul>
 * Checks:
 * <li>order of keys in isolated nodes
 * <li>keys fit inside range given by parent node
 * <li>sibling pointers match
 * <li>GSPP
 * </ul>
 */
class ConsistencyChecker<KEY>
{
    private final TreeNode<KEY,?> node;
    private final KEY readKey;
    private final Comparator<KEY> comparator;
    private final Layout<KEY,?> layout;
    private final List<RightmostInChain> rightmostPerLevel = new ArrayList<>();
    private final long stableGeneration;
    private final long unstableGeneration;

    ConsistencyChecker( TreeNode<KEY,?> node, Layout<KEY,?> layout, long stableGeneration, long unstableGeneration )
    {
        this.node = node;
        this.readKey = layout.newKey();
        this.comparator = node.keyComparator();
        this.layout = layout;
        this.stableGeneration = stableGeneration;
        this.unstableGeneration = unstableGeneration;
    }

    public boolean check( PageCursor cursor, long expectedGen ) throws IOException
    {
        assertOnTreeNode( cursor );
        KeyRange<KEY> openRange = new KeyRange<>( comparator, null, null, layout, null );
        boolean result = checkSubtree( cursor, openRange, expectedGen, 0 );

        // Assert that rightmost node on each level has empty right sibling.
        rightmostPerLevel.forEach( RightmostInChain::assertLast );
        return result;
    }

    /**
     * Checks so that all pages between {@link IdSpace#MIN_TREE_NODE_ID} and highest allocated id
     * are either in use in the tree, on the free-list or free-list nodes.
     *
     * @param cursor {@link PageCursor} to use for reading.
     * @param lastId highest allocated id in the store.
     * @param freelistIds page ids making up free-list pages and page ids on the free-list.
     * @return {@code true} if all pages are taken, otherwise {@code false}. Also is compatible with java
     * assert calls.
     * @throws IOException on {@link PageCursor} error.
     */
    public boolean checkSpace( PageCursor cursor, long lastId, PrimitiveLongIterator freelistIds ) throws IOException
    {
        assertOnTreeNode( cursor );

        // TODO: limitation, can't run on an index larger than Integer.MAX_VALUE pages (which is fairly large)
        long highId = lastId + 1;
        BitSet seenIds = new BitSet( toIntExact( highId ) );
        while ( freelistIds.hasNext() )
        {
            addToSeenList( seenIds, freelistIds.next(), lastId );
        }

        // Traverse the tree
        do
        {
            // One level at the time
            long leftmostSibling = cursor.getCurrentPageId();
            addToSeenList( seenIds, leftmostSibling, lastId );

            // Go right through all siblings
            traverseAndAddRightSiblings( cursor, seenIds, lastId );

            // Then go back to the left-most node on this level
            node.goTo( cursor, "back", leftmostSibling );
        }
        // And continue down to next level if this level was an internal level
        while ( goToLeftmostChild( cursor ) );

        assertAllIdsOccupied( highId, seenIds );
        return true;
    }

    private boolean goToLeftmostChild( PageCursor cursor ) throws IOException
    {
        boolean isInternal;
        long leftmostSibling = -1;
        do
        {
            isInternal = node.isInternal( cursor );
            if ( isInternal )
            {
                leftmostSibling = node.childAt( cursor, 0, stableGeneration, unstableGeneration );
            }
        }
        while ( cursor.shouldRetry() );

        if ( isInternal )
        {
            node.goTo( cursor, "child", leftmostSibling );
        }
        return isInternal;
    }

    private static void assertAllIdsOccupied( long highId, BitSet seenIds )
    {
        long expectedNumberOfPages = highId - IdSpace.MIN_TREE_NODE_ID;
        if ( seenIds.cardinality() != expectedNumberOfPages )
        {
            StringBuilder builder = new StringBuilder( "[" );
            int index = (int) IdSpace.MIN_TREE_NODE_ID;
            int count = 0;
            while ( index >= 0 && index < highId )
            {
                index = seenIds.nextClearBit( index );
                if ( index != -1 )
                {
                    if ( count++ > 0 )
                    {
                        builder.append( "," );
                    }
                    builder.append( index );
                    index++;
                }
            }
            builder.append( "]" );
            throw new RuntimeException( "There are " + count + " unused pages in the store:" + builder );
        }
    }

    private void traverseAndAddRightSiblings( PageCursor cursor, BitSet seenIds, long lastId ) throws IOException
    {
        long rightSibling;
        do
        {
            do
            {
                rightSibling = node.rightSibling( cursor, stableGeneration, unstableGeneration );
            }
            while ( cursor.shouldRetry() );

            if ( TreeNode.isNode( rightSibling ) )
            {
                node.goTo( cursor, "right sibling", rightSibling );
                addToSeenList( seenIds, pointer( rightSibling ), lastId );
            }
        }
        while ( TreeNode.isNode( rightSibling ) );
    }

    private static void addToSeenList( BitSet target, long id, long lastId )
    {
        int index = toIntExact( id );
        if ( target.get( index ) )
        {
            throw new IllegalStateException( id + " already seen" );
        }
        if ( id > lastId )
        {
            throw new IllegalStateException( "Unexpectedly high id " + id + " seen when last id is " + lastId );
        }
        target.set( index );
    }

    static void assertOnTreeNode( PageCursor cursor ) throws IOException
    {
        byte nodeType;
        boolean isInternal;
        boolean isLeaf;
        do
        {
            nodeType = TreeNode.nodeType( cursor );
            isInternal = TreeNode.isInternal( cursor );
            isLeaf = TreeNode.isLeaf( cursor );
        }
        while ( cursor.shouldRetry() );

        if ( nodeType != TreeNode.NODE_TYPE_TREE_NODE )
        {
            throw new IllegalArgumentException( "Cursor is not pinned to a tree node page. pageId:" +
                    cursor.getCurrentPageId() );
        }
        if ( !isInternal && !isLeaf )
        {
            throw new IllegalArgumentException( "Cursor is not pinned to a page containing a tree node. pageId:" +
                    cursor.getCurrentPageId() );
        }
    }

    private boolean checkSubtree( PageCursor cursor, KeyRange<KEY> range, long expectedGen, int level )
            throws IOException
    {
        boolean isInternal = false;
        boolean isLeaf = false;
        int keyCount;
        long heir;
        long heirGen;

        long leftSiblingPointer;
        long rightSiblingPointer;
        long leftSiblingPointerGen;
        long rightSiblingPointerGen;
        long currentNodeGen;

        do
        {
            // check header pointers
            assertNoCrashOrBrokenPointerInGSPP(
                    cursor, stableGeneration, unstableGeneration, "LeftSibling", TreeNode.BYTE_POS_LEFTSIBLING, node );
            assertNoCrashOrBrokenPointerInGSPP(
                    cursor, stableGeneration, unstableGeneration, "RightSibling", TreeNode.BYTE_POS_RIGHTSIBLING, node );
            assertNoCrashOrBrokenPointerInGSPP(
                    cursor, stableGeneration, unstableGeneration, "Heir", TreeNode.BYTE_POS_HEIR, node );

            // for assertSiblings
            leftSiblingPointer = node.leftSibling( cursor, stableGeneration, unstableGeneration );
            rightSiblingPointer = node.rightSibling( cursor, stableGeneration, unstableGeneration );
            leftSiblingPointerGen = node.pointerGen( cursor, leftSiblingPointer );
            rightSiblingPointerGen = node.pointerGen( cursor, rightSiblingPointer );
            leftSiblingPointer = pointer( leftSiblingPointer );
            rightSiblingPointer = pointer( rightSiblingPointer );
            currentNodeGen = node.gen( cursor );

            heir = node.heir( cursor, stableGeneration, unstableGeneration );
            heirGen = node.pointerGen( cursor, heir );

            keyCount = node.keyCount( cursor );
            if ( keyCount > node.internalMaxKeyCount() && keyCount > node.leafMaxKeyCount() )
            {
                cursor.setCursorException( "Unexpected keyCount:" + keyCount );
                continue;
            }
            assertKeyOrder( cursor, range, keyCount );
            isInternal = node.isInternal( cursor );
            isLeaf = node.isLeaf( cursor );
        }
        while ( cursor.shouldRetry() );
        checkAfterShouldRetry( cursor );

        if ( !isInternal && !isLeaf )
        {
            throw new TreeInconsistencyException( "Page:" + cursor.getCurrentPageId() + " at level:" + level +
                    " isn't a tree node, parent expected range " + range );
        }

        assertPointerGenMatchesGen( cursor, currentNodeGen, expectedGen );
        assertSiblings( cursor, currentNodeGen, leftSiblingPointer, leftSiblingPointerGen, rightSiblingPointer,
                rightSiblingPointerGen, level );
        checkHeirPointerGen( cursor, heir, heirGen );

        if ( isInternal )
        {
            assertSubtrees( cursor, range, keyCount, level );
        }
        return true;
    }

    private static void assertPointerGenMatchesGen( PageCursor cursor, long nodeGen, long expectedGen )
    {
        assert nodeGen <= expectedGen : "Expected node:" + cursor.getCurrentPageId() + " gen:" + nodeGen +
                " to be ≤ pointer gen:" + expectedGen;
    }

    private void checkHeirPointerGen( PageCursor cursor, long heir, long heirGen )
            throws IOException
    {
        if ( TreeNode.isNode( heir ) )
        {
            cursor.setCursorException( "WARNING: we ended up on an old generation " + cursor.getCurrentPageId() +
                    " which had heir:" + pointer( heir ) );
            long origin = cursor.getCurrentPageId();
            node.goTo( cursor, "heir", heir );
            try
            {
                long nodeGen;
                do
                {
                    nodeGen = node.gen( cursor );
                }
                while ( cursor.shouldRetry() );

                assertPointerGenMatchesGen( cursor, nodeGen, heirGen );
            }
            finally
            {
                node.goTo( cursor, "back", origin );
            }
        }
    }

    // Assumption: We traverse the tree from left to right on every level
    private void assertSiblings( PageCursor cursor, long currentNodeGen, long leftSiblingPointer,
            long leftSiblingPointerGen, long rightSiblingPointer, long rightSiblingPointerGen, int level )
    {
        // If this is the first time on this level, we will add a new entry
        for ( int i = rightmostPerLevel.size(); i <= level; i++ )
        {
            rightmostPerLevel.add( i, new RightmostInChain() );
        }
        RightmostInChain rightmost = rightmostPerLevel.get( level );

        rightmost.assertNext( cursor, currentNodeGen, leftSiblingPointer, leftSiblingPointerGen, rightSiblingPointer,
                rightSiblingPointerGen );
    }

    private void assertSubtrees( PageCursor cursor, KeyRange<KEY> range, int keyCount, int level )
            throws IOException
    {
        long pageId = cursor.getCurrentPageId();
        KEY prev = layout.newKey();
        KeyRange<KEY> childRange;

        // Check children, all except the last one
        int pos = 0;
        while ( pos < keyCount )
        {
            long child;
            long childGen;
            do
            {
                child = childAt( cursor, pos );
                childGen = node.pointerGen( cursor, child );
                node.keyAt( cursor, readKey, pos );
            }
            while ( cursor.shouldRetry() );
            checkAfterShouldRetry( cursor );

            childRange = range.restrictRight( readKey );
            if ( pos > 0 )
            {
                childRange = range.restrictLeft( prev );
            }

            node.goTo( cursor, "child at pos " + pos, child );
            checkSubtree( cursor, childRange, childGen, level + 1 );

            node.goTo( cursor, "parent", pageId );

            layout.copyKey( readKey, prev );
            pos++;
        }

        // Check last child
        long child;
        long childGen;
        do
        {
            child = childAt( cursor, pos );
            childGen = node.pointerGen( cursor, child );
        }
        while ( cursor.shouldRetry() );
        checkAfterShouldRetry( cursor );

        node.goTo( cursor, "child at pos " + pos, child );
        childRange = range.restrictLeft( prev );
        checkSubtree( cursor, childRange, childGen, level + 1 );
        node.goTo( cursor, "parent", pageId );
    }

    private static void checkAfterShouldRetry( PageCursor cursor ) throws CursorException
    {
        checkOutOfBounds( cursor );
        cursor.checkAndClearCursorException();
    }

    private long childAt( PageCursor cursor, int pos )
    {
        assertNoCrashOrBrokenPointerInGSPP(
                cursor, stableGeneration, unstableGeneration, "Child", node.childOffset( pos ), node );
        return node.childAt( cursor, pos, stableGeneration, unstableGeneration );
    }

    private void assertKeyOrder( PageCursor cursor, KeyRange<KEY> range, int keyCount )
    {
        KEY prev = layout.newKey();
        boolean first = true;
        for ( int pos = 0; pos < keyCount; pos++ )
        {
            node.keyAt( cursor, readKey, pos );
            if ( !range.inRange( readKey ) )
            {
                cursor.setCursorException( "Expected range for this node is " + range + " but found " + readKey +
                        " in position " + pos + ", with key count " + keyCount );
            }
            if ( !first )
            {
                if ( comparator.compare( prev, readKey ) >= 0 )
                {
                    cursor.setCursorException( "Non-unique key " + readKey );
                }
            }
            else
            {
                first = false;
            }
            layout.copyKey( readKey, prev );
        }
    }

    static void assertNoCrashOrBrokenPointerInGSPP( PageCursor cursor, long stableGeneration, long unstableGeneration,
            String pointerFieldName, int offset, TreeNode<?,?> treeNode )
    {
        cursor.setOffset( offset );
        long currentNodeId = cursor.getCurrentPageId();
        // A
        long generationA = GenSafePointer.readGeneration( cursor );
        long pointerA = GenSafePointer.readPointer( cursor );
        short checksumA = GenSafePointer.readChecksum( cursor );
        boolean correctChecksumA = GenSafePointer.checksumOf( generationA, pointerA ) == checksumA;
        byte stateA = GenSafePointerPair.pointerState(
                stableGeneration, unstableGeneration, generationA, pointerA, correctChecksumA );
        boolean okA = stateA != GenSafePointerPair.BROKEN && stateA != GenSafePointerPair.CRASH;

        // B
        long generationB = GenSafePointer.readGeneration( cursor );
        long pointerB = GenSafePointer.readPointer( cursor );
        short checksumB = GenSafePointer.readChecksum( cursor );
        boolean correctChecksumB = GenSafePointer.checksumOf( generationB, pointerB ) == checksumB;
        byte stateB = GenSafePointerPair.pointerState(
                stableGeneration, unstableGeneration, generationB, pointerB, correctChecksumB );
        boolean okB = stateB != GenSafePointerPair.BROKEN && stateB != GenSafePointerPair.CRASH;

        if ( !(okA && okB) )
        {
            boolean isInternal = treeNode.isInternal( cursor );
            String type = isInternal ? "internal" : "leaf";
            cursor.setCursorException( format(
                    "GSPP state found that was not ok in %s field in %s node with id %d%n  slotA[%s]%n  slotB[%s]",
                    pointerFieldName, type, currentNodeId,
                    stateToString( generationA, pointerA, stateA ),
                    stateToString( generationB, pointerB, stateB ) ) );
        }
    }

    private static String stateToString( long generationA, long pointerA, byte stateA )
    {
        return format( "generation=%d, pointer=%d, state=%s",
                generationA, pointerA, GenSafePointerPair.pointerStateName( stateA ) );
    }

    private static class KeyRange<KEY>
    {
        private final Comparator<KEY> comparator;
        private final KEY fromInclusive;
        private final KEY toExclusive;
        private final Layout<KEY,?> layout;
        private final KeyRange<KEY> superRange;

        private KeyRange( Comparator<KEY> comparator, KEY fromInclusive, KEY toExclusive, Layout<KEY,?> layout,
                KeyRange<KEY> superRange )
        {
            this.comparator = comparator;
            this.superRange = superRange;
            this.fromInclusive = fromInclusive == null ? null : layout.copyKey( fromInclusive, layout.newKey() );
            this.toExclusive = toExclusive == null ? null : layout.copyKey( toExclusive, layout.newKey() );
            this.layout = layout;
        }

        boolean inRange( KEY key )
        {
            if ( fromInclusive != null )
            {
                if ( toExclusive != null )
                {
                    return comparator.compare( key, fromInclusive ) >= 0 && comparator.compare( key, toExclusive ) < 0;
                }
                return comparator.compare( key, fromInclusive ) >= 0;
            }
            return toExclusive == null || comparator.compare( key, toExclusive ) < 0;
        }

        KeyRange<KEY> restrictLeft( KEY left )
        {
            if ( fromInclusive == null || comparator.compare( fromInclusive, left ) < 0 )
            {
                return new KeyRange<>( comparator, left, toExclusive, layout, this );
            }
            return new KeyRange<>( comparator, fromInclusive, toExclusive, layout, this );
        }

        KeyRange<KEY> restrictRight( KEY right )
        {
            if ( toExclusive == null || comparator.compare( toExclusive, right ) > 0 )
            {
                return new KeyRange<>( comparator, fromInclusive, right, layout, this );
            }
            return new KeyRange<>( comparator, fromInclusive, toExclusive, layout, this );
        }

        @Override
        public String toString()
        {
            return (superRange != null ? format( "%s%n", superRange ) : "") + fromInclusive + " ≤ key < " + toExclusive;
        }
    }
}