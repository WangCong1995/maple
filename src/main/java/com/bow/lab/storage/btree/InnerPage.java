package com.bow.lab.storage.btree;

import java.util.List;

import com.bow.lab.storage.heap.PageTupleUtil;
import com.bow.maple.expressions.LiteralTuple;
import com.bow.lab.indexes.IndexFileInfo;
import com.bow.maple.relations.ColumnInfo;
import com.bow.maple.relations.Tuple;
import com.bow.maple.storage.DBPage;
import com.bow.maple.storage.btreeindex.BTreeIndexManager;
import com.bow.maple.storage.btreeindex.BTreeIndexPageTuple;
import com.bow.maple.storage.btreeindex.LeafPage;
import com.bow.maple.storage.btreeindex.LeafPageOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * This class wraps a {@link DBPage} object that is an inner page in the B
 * <sup>+</sup> tree implementation, to provide some of the basic
 * inner-page-management operations necessary for the indexing structure.
 * </p>
 * <p>
 * Operations involving individual leaf-pages are provided by the
 * {@link LeafPage} wrapper-class. Higher-level operations involving multiple
 * leaves and/or inner pages of the B<sup>+</sup> tree structure, are provided
 * by the {@link LeafPageOperations} and {@link InnerPageOperations} classes.
 * </p>
 *
 * |pageNo1|key|pageNo2|key|pageNo3|key|pageNo4|
 *
 */
public class InnerPage {

    private static Logger logger = LoggerFactory.getLogger(InnerPage.class);

    public static final int OFFSET_PAGE_TYPE = 0;

    /**
     * The offset where the number of pointer entries is stored in the page. The
     * page will hold one less keys than pointers, since each key must be
     * sandwiched between two pointers.
     */
    public static final int OFFSET_NUM_POINTERS = 3;

    /** The offset of the first pointer in the non-leaf page. */
    public static final int OFFSET_FIRST_POINTER = 5;

    private DBPage dbPage;

    /**
     * Information about the index itself, such as what file it is stored in,
     * its name, the columns in the index, and so forth.
     */
    private IndexFileInfo idxFileInfo;

    /** The number of pointers stored within this non-leaf page. */
    private int numPointers;

    /**
     * An array of the offsets where the pointers are stored in this non-leaf
     * page. Each pointer points to another page within the index file. There is
     * one more pointer than keys, since each key must be sandwiched between two
     * pointers.
     */
    private int[] pointerOffsets;

    /**
     * An array of the keys stored in this non-leaf page. Each key also stores
     * the file-pointer for the associated tuple, as the last value in the key.
     */
    private BTreeIndexPageTuple[] keys;

    /**
     * The total size of all data (pointers + keys + initial values) stored
     * within this leaf page. This is also the offset at which we can start
     * writing more data without overwriting anything.
     */
    private int endOffset;

    /**
     * Initialize the inner-page wrapper class for the specified index page. The
     * contents of the inner-page are cached in the fields of the wrapper
     * object.
     *
     * @param dbPage the data page from the index file to wrap
     * @param idxFileInfo the general descriptive information about the index
     */
    public InnerPage(DBPage dbPage, IndexFileInfo idxFileInfo) {
        this.dbPage = dbPage;
        this.idxFileInfo = idxFileInfo;

        loadPageContents();
    }

    /**
     * This static helper function initializes a {@link DBPage} object's
     * contents with the type and detail values that will allow a new
     * {@code InnerPage} wrapper to be instantiated for the page, and then it
     * returns a wrapper object for the page. This version of the {@code init}
     * function creates an inner page that is initially empty.
     *
     * @param dbPage the page to initialize as an inner-page.
     *
     * @param idxFileInfo details about the index that the inner-page is for
     *
     * @return a newly initialized {@code InnerPage} object wrapping the page
     */
    public static InnerPage init(DBPage dbPage, IndexFileInfo idxFileInfo) {
        dbPage.writeByte(OFFSET_PAGE_TYPE, BTreeIndexManager.BTREE_INNER_PAGE);
        dbPage.writeShort(OFFSET_NUM_POINTERS, 0);
        return new InnerPage(dbPage, idxFileInfo);
    }

    /**
     * This static helper function initializes a {@link DBPage} object's
     * contents with the type and detail values that will allow a new
     * {@code InnerPage} wrapper to be instantiated for the page, and then it
     * returns a wrapper object for the page. This version of the {@code init}
     * function creates an inner page that initially contains the specified
     * page-pointers and key value.
     *
     * @param dbPage the page to initialize as an inner-page.
     *
     * @param idxFileInfo details about the index that the inner-page is for
     *
     * @param pagePtr1 to the left of {@code key1}
     * @param key1 the first key to store in the inner page
     * @param pagePtr2 the right of {@code key1}
     * @return a newly initialized {@code InnerPage} object wrapping the page
     */
    public static InnerPage init(DBPage dbPage, IndexFileInfo idxFileInfo, int pagePtr1, Tuple key1, int pagePtr2) {
        dbPage.writeByte(OFFSET_PAGE_TYPE, BTreeIndexManager.BTREE_INNER_PAGE);
        // Write the first contents of the non-leaf page: [ptr0, key0, ptr1]
        int offset = OFFSET_FIRST_POINTER;
        // 写pageNo1
        dbPage.writeShort(offset, pagePtr1);
        offset += 2;
        // 写key
        offset = PageTupleUtil.storeTuple(dbPage, offset, idxFileInfo.getIndexSchema(), key1);
        // 写pageNo2
        dbPage.writeShort(offset, pagePtr2);
        dbPage.writeShort(OFFSET_NUM_POINTERS, 2);
        return new InnerPage(dbPage, idxFileInfo);
    }

    /**
     * 更新此页的缓存
     */
    private void loadPageContents() {
        numPointers = dbPage.readUnsignedShort(OFFSET_NUM_POINTERS);
        if (numPointers > 0) {
            pointerOffsets = new int[numPointers];
            // key比pointer少1
            keys = new BTreeIndexPageTuple[numPointers - 1];
            List<ColumnInfo> colInfos = idxFileInfo.getIndexSchema();

            // Handle first pointer + key separately since we know their offsets
            pointerOffsets[0] = OFFSET_FIRST_POINTER;
            BTreeIndexPageTuple key = new BTreeIndexPageTuple(dbPage, OFFSET_FIRST_POINTER + 2, colInfos);
            keys[0] = key;

            // Handle all the pointer/key pairs.不包括最后一个pointer
            int keyEndOffset;
            for (int i = 1; i < numPointers - 1; i++) {
                // Next pointer starts where the previous key ends.
                keyEndOffset = key.getEndOffset();
                pointerOffsets[i] = keyEndOffset;
                // Next key starts after the next pointer.
                key = new BTreeIndexPageTuple(dbPage, keyEndOffset + 2, colInfos);
                keys[i] = key;
            }
            //最后一个pointer
            keyEndOffset = key.getEndOffset();
            pointerOffsets[numPointers - 1] = keyEndOffset;
            endOffset = keyEndOffset + 2;
        } else {
            // There are no entries (pointers + keys).
            endOffset = OFFSET_FIRST_POINTER;
            pointerOffsets = null;
            keys = null;
        }
    }

    /**
     * Returns the high-level details for the index that this page is a part of.
     *
     * @return the high-level details for the index
     */
    public IndexFileInfo getIndexFileInfo() {
        return idxFileInfo;
    }

    /**
     * Returns the page-number of this leaf page.
     *
     * @return the page-number of this leaf page.
     */
    public int getPageNo() {
        return dbPage.getPageNo();
    }

    /**
     * Returns the number of pointers currently stored in this inner page. The
     * number of keys is always one less than the number of pointers, since each
     * key must have a pointer on both sides.
     *
     * @return the number of pointers in this inner page.
     */
    public int getNumPointers() {
        return numPointers;
    }

    /**
     * Returns the number of keys currently stored in this inner page. The
     * number of keys is always one less than the number of pointers, since each
     * key must have a pointer on both sides.
     *
     * @return the number of keys in this inner page.
     *
     * @throws IllegalStateException if the inner page contains 0 pointers
     */
    public int getNumKeys() {
        if (numPointers < 1) {
            throw new IllegalStateException("Inner page contains no " + "pointers.  Number of keys is meaningless.");
        }

        return numPointers - 1;
    }

    /**
     * Returns the amount of space available in this inner page, in bytes.
     *
     * @return the amount of space available in this inner page, in bytes.
     */
    public int getFreeSpace() {
        return dbPage.getPageSize() - endOffset;
    }

    /**
     * 第index个entry的pointer指向的页
     * @param index 第index个entry
     * @return 第pointer页
     */
    public int getPointer(int index) {
        return dbPage.readUnsignedShort(pointerOffsets[index]);
    }

    /**
     * Returns the key at the specified index.
     *
     * @param index the index of the key to retrieve
     *
     * @return the key at that index
     */
    public BTreeIndexPageTuple getKey(int index) {
        return keys[index];
    }

    /**
     * 查找页号pointer在此inner page中的第几个entry上
     * @param pointer the page-pointer to find in this inner page
     * @return the index of the page-pointer if found, or -1 if not found
     */
    public int getIndexOfPointer(int pointer) {
        for (int i = 0; i < getNumPointers(); i++) {
            if (getPointer(i) == pointer)
                return i;
        }
        return -1;
    }

    public void replaceKey(int index, Tuple key) {
        int oldStart = keys[index].getOffset();
        int oldLen = keys[index].getEndOffset() - oldStart;
        List<ColumnInfo> colInfos = idxFileInfo.getIndexSchema();
        int newLen = PageTupleUtil.getTupleStorageSize(colInfos, key);
        if (newLen != oldLen) {
            // Need to adjust the amount of space the key takes.
            if (endOffset + newLen - oldLen > dbPage.getPageSize()) {
                throw new IllegalArgumentException("New key-value is too large to fit in non-leaf page.");
            }
            dbPage.moveDataRange(oldStart + oldLen, oldStart + newLen, endOffset - oldStart - oldLen);
        }
        PageTupleUtil.storeTuple(dbPage, oldStart, colInfos, key);
        // Reload the page contents.
        loadPageContents();
    }

    /**
     * 将(pagePtr1,key1,pagePtr2)加入到inner page，pagePtr1必须要在此页中已经存在，否则报错。
     * 从此inner page中找到pagePtr1，然后将(key1,pagePtr2)加入
     * @param pagePtr1 在此inner page中已存在，否则报错
     * @param key1 紧跟pagePtr1
     * @param pagePtr2 紧跟key1
     * @throws IllegalArgumentException e
     */
    public void addEntry(int pagePtr1, Tuple key1, int pagePtr2) {

        if (logger.isTraceEnabled()) {
            logger.trace("Non-leaf page " + getPageNo() + " contents before adding entry:\n" + toFormattedString());
        }
        int i;
        for (i = 0; i < numPointers; i++) {
            if (getPointer(i) == pagePtr1){
                break;
            }
        }
        logger.debug("Found page-pointer {} in index {}", pagePtr1, i);
        if (i == numPointers) {
            throw new IllegalArgumentException(
                    "Can't find initial page-pointer " + pagePtr1 + " in non-leaf page " + getPageNo());
        }

        // 找出从哪里开始插入新值
        int oldKeyStart;
        if (i < numPointers - 1) {
            oldKeyStart = keys[i].getOffset();
        } else {
            //最后一个page pointer才是pagePtr1，则新值(key1,pagePtr2)从endOffset开始
            oldKeyStart = endOffset;
        }
        int len = endOffset - oldKeyStart;

        // 计算新entry<key,pageNo>的大小
        List<ColumnInfo> colInfos = idxFileInfo.getIndexSchema();
        int newKeySize = PageTupleUtil.getTupleStorageSize(colInfos, key1);
        int newEntrySize = newKeySize + 2;
        if (endOffset + newEntrySize > dbPage.getPageSize()) {
            throw new IllegalArgumentException(
                    "New key-value and " + "page-pointer are too large to fit in non-leaf page.");
        }

        if (len > 0) {
            // 腾出空间
            dbPage.moveDataRange(oldKeyStart, oldKeyStart + newEntrySize, len);
        }

        // 写入 new key/pointer values.
        PageTupleUtil.storeTuple(dbPage, oldKeyStart, colInfos, key1);
        dbPage.writeShort(oldKeyStart + newKeySize, pagePtr2);

        // 更新entry总数
        dbPage.writeShort(OFFSET_NUM_POINTERS, numPointers + 1);
        loadPageContents();

        if (logger.isTraceEnabled()) {
            logger.trace("Non-leaf page " + getPageNo() + " contents after adding entry:\n" + toFormattedString());
        }
    }

    /**
     * <p>
     * This helper function moves the specified number of page-pointers to the
     * left sibling of this inner node. The data is copied in one shot so that
     * the transfer will be fast, and the various associated bookkeeping values
     * in both inner pages are updated.
     * </p>
     * <p>
     * Of course, moving a subset of the page-pointers to a sibling will leave a
     * key without a pointer on one side; this key is promoted up to the parent
     * of the inner node. Additionally, an existing parent-key can be provided
     * by the caller, which should be inserted before the new pointers being
     * moved into the sibling node.
     * </p>
     *
     * @param leftSibling the left sibling of this inner node in the index file
     *
     * @param count the number of pointers to move to the left sibling
     *
     * @param parentKey If this inner node and the sibling already have a parent
     *        node, this is the key between the two nodes' page-pointers in the
     *        parent node. If the two nodes don't have a parent (i.e. because an
     *        inner node is being split into two nodes and the depth of the tree
     *        is being increased) then this value will be {@code null}.
     *
     * @return the key that should go into the parent node, between the
     *         page-pointers for this node and its sibling
     *
     * @todo (Donnie) When support for deletion is added to the index
     *       implementation, we will need to support the case when the incoming
     *       {@code parentKey} is non-{@code null}, but the returned key is
     *       {@code null} because one of the two siblings' pointers will be
     *       removed.
     */
    public LiteralTuple movePointersLeft(InnerPage leftSibling, int count, Tuple parentKey) {

        if (count < 0 || count > numPointers) {
            throw new IllegalArgumentException("count must be in range [0, " + numPointers + "), got " + count);
        }

        // The parent-key can be null if we are splitting a page into two pages.
        // However, this situation is only valid if the right sibling is EMPTY.
        int parentKeyLen = 0;
        if (parentKey != null) {
            parentKeyLen = PageTupleUtil.getTupleStorageSize(idxFileInfo.getIndexSchema(), parentKey);
        } else {
            if (leftSibling.getNumPointers() != 0) {
                throw new IllegalStateException(
                        "Cannot move pointers to " + "non-empty sibling if no parent-key is specified!");
            }
        }

        // Copy the range of pointer-data to the destination page, making sure
        // to include the parent-key before the first pointer from the right
        // page. Then update the count of pointers in the destination page.

        int moveEndOffset = pointerOffsets[count] + 2;
        int len = moveEndOffset - OFFSET_FIRST_POINTER;

        if (parentKey != null) {
            // Write in the parent key
            PageTupleUtil.storeTuple(leftSibling.dbPage, leftSibling.endOffset, idxFileInfo.getIndexSchema(), parentKey);
        }

        // Copy the pointer data across
        leftSibling.dbPage.write(leftSibling.endOffset + parentKeyLen, dbPage.getPageData(), OFFSET_FIRST_POINTER, len);

        if (parentKey != null) {
            // Update the entry-count
            leftSibling.dbPage.writeShort(OFFSET_NUM_POINTERS, leftSibling.numPointers + count);
        }

        // Finally, pull out the new parent key, and then clear the area in the
        // source page that no longer holds data.

        LiteralTuple newParentKey = null;
        if (count < numPointers) {
            // There's a key to the right of the last pointer we moved. This
            // will become the new parent key.
            BTreeIndexPageTuple key = keys[count - 1];
            int keyEndOff = key.getEndOffset();
            newParentKey = new LiteralTuple(key);

            // Slide left the remainder of the data.
            dbPage.moveDataRange(keyEndOff, OFFSET_FIRST_POINTER, endOffset - keyEndOff);
            dbPage.setDataRange(OFFSET_FIRST_POINTER + endOffset - keyEndOff, keyEndOff - OFFSET_FIRST_POINTER,
                    (byte) 0);
        } else {
            // The entire page is being emptied, so clear out all the data.
            dbPage.setDataRange(OFFSET_FIRST_POINTER, endOffset - OFFSET_FIRST_POINTER, (byte) 0);
        }
        dbPage.writeShort(OFFSET_NUM_POINTERS, numPointers - count);

        // Update the cached info for both non-leaf pages.
        loadPageContents();
        leftSibling.loadPageContents();

        return newParentKey;
    }

    /**
     * <p>
     * This helper function moves the specified number of page-pointers to the
     * right sibling of this inner node. The data is copied in one shot so that
     * the transfer will be fast, and the various associated bookkeeping values
     * in both inner pages are updated.
     * </p>
     * <p>
     * Of course, moving a subset of the page-pointers to a sibling will leave a
     * key without a pointer on one side; this key is promoted up to the parent
     * of the inner node. Additionally, an existing parent-key can be provided
     * by the caller, which should be inserted before the new pointers being
     * moved into the sibling node.
     * </p>
     *
     * @param rightSibling the right sibling of this inner node in the index
     *        file
     *
     * @param count the number of pointers to move to the right sibling
     *
     * @param parentKey If this inner node and the sibling already have a parent
     *        node, this is the key between the two nodes' page-pointers in the
     *        parent node. If the two nodes don't have a parent (i.e. because an
     *        inner node is being split into two nodes and the depth of the tree
     *        is being increased) then this value will be {@code null}.
     *
     * @return the key that should go into the parent node, between the
     *         page-pointers for this node and its sibling
     *
     * @todo (Donnie) When support for deletion is added to the index
     *       implementation, we will need to support the case when the incoming
     *       {@code parentKey} is non-{@code null}, but the returned key is
     *       {@code null} because one of the two siblings' pointers will be
     *       removed.
     */
    public LiteralTuple movePointersRight(InnerPage rightSibling, int count, Tuple parentKey) {

        if (count < 0 || count > numPointers) {
            throw new IllegalArgumentException("count must be in range [0, " + numPointers + "), got " + count);
        }

        if (logger.isTraceEnabled()) {
            logger.trace(
                    "Non-leaf page " + getPageNo() + " contents before moving pointers right:\n" + toFormattedString());
        }

        int startPointerIndex = numPointers - count;
        int startOffset = pointerOffsets[startPointerIndex];
        int len = endOffset - startOffset;

        logger.debug("Moving everything after pointer " + startPointerIndex + " to right sibling.  Start offset = "
                + startOffset + ", end offset = " + endOffset + ", len = " + len);

        // The parent-key can be null if we are splitting a page into two pages.
        // However, this situation is only valid if the right sibling is EMPTY.
        int parentKeyLen = 0;
        if (parentKey != null) {
            parentKeyLen = PageTupleUtil.getTupleStorageSize(idxFileInfo.getIndexSchema(), parentKey);
        } else {
            if (rightSibling.getNumPointers() != 0) {
                throw new IllegalStateException(
                        "Cannot move pointers to " + "non-empty sibling if no parent-key is specified!");
            }
        }

        // Copy the range of pointer-data to the destination page, making sure
        // to include the parent-key after the last pointer from the left page.
        // Then update the count of pointers in the destination page.

        if (parentKey != null) {
            // Make room for the data
            rightSibling.dbPage.moveDataRange(OFFSET_FIRST_POINTER, OFFSET_FIRST_POINTER + len + parentKeyLen,
                    rightSibling.endOffset - OFFSET_FIRST_POINTER);
        }

        // Copy the pointer data across
        rightSibling.dbPage.write(OFFSET_FIRST_POINTER, dbPage.getPageData(), startOffset, len);

        if (parentKey != null) {
            // Write in the parent key
            PageTupleUtil.storeTuple(rightSibling.dbPage, OFFSET_FIRST_POINTER + len, idxFileInfo.getIndexSchema(),
                    parentKey);
        }

        // Update the entry-count
        rightSibling.dbPage.writeShort(OFFSET_NUM_POINTERS, rightSibling.numPointers + count);

        // Finally, pull out the new parent key, and then clear the area in the
        // source page that no longer holds data.

        LiteralTuple newParentKey = null;
        if (count < numPointers) {
            // There's a key to the left of the last pointer we moved. This
            // will become the new parent key.
            BTreeIndexPageTuple key = keys[startPointerIndex - 1];
            int keyOff = key.getOffset();
            newParentKey = new LiteralTuple(key);

            // Cut down the remainder of the data.
            dbPage.setDataRange(keyOff, endOffset - keyOff, (byte) 0);
        } else {
            // The entire page is being emptied, so clear out all the data.
            dbPage.setDataRange(OFFSET_FIRST_POINTER, endOffset - OFFSET_FIRST_POINTER, (byte) 0);
        }
        dbPage.writeShort(OFFSET_NUM_POINTERS, numPointers - count);

        // Update the cached info for both non-leaf pages.
        loadPageContents();
        rightSibling.loadPageContents();

        if (logger.isTraceEnabled()) {
            logger.trace(
                    "Non-leaf page " + getPageNo() + " contents after moving pointers right:\n" + toFormattedString());

            logger.trace("Right-sibling page " + rightSibling.getPageNo() + " contents after moving pointers right:\n"
                    + rightSibling.toFormattedString());
        }

        return newParentKey;
    }

    /**
     * <p>
     * This helper method creates a formatted string containing the contents of
     * the inner page, including the pointers and the intervening keys.
     * </p>
     * <p>
     * It is strongly suggested that this method should only be used for
     * trace-level output, since otherwise the output will become overwhelming.
     * </p>
     *
     * @return a formatted string containing the contents of the inner page
     */
    public String toFormattedString() {
        StringBuilder buf = new StringBuilder();

        buf.append(String.format("Inner page %d contains %d pointers%n", getPageNo(), numPointers));

        if (numPointers > 0) {
            for (int i = 0; i < numPointers - 1; i++) {
                buf.append(String.format("    Pointer %d = page %d%n", i, getPointer(i)));
                buf.append(String.format("    Key %d = %s%n", i, getKey(i)));
            }
            buf.append(String.format("    Pointer %d = page %d%n", numPointers - 1, getPointer(numPointers - 1)));
        }

        return buf.toString();
    }
}
