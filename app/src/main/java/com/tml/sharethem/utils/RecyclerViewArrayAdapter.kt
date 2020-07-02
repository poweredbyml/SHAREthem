package com.tml.sharethem.utils

import androidx.recyclerview.widget.RecyclerView

abstract class RecyclerViewArrayAdapter<T, VH : RecyclerView.ViewHolder?>(protected var mObjects: MutableList<T?>) : RecyclerView.Adapter<VH>() {

    /**
     * Adds the specified object at the end of the array.
     *
     * @param object The object to add at the end of the array.
     */
    fun add(`object`: T) {
        mObjects.add(`object`)
        notifyItemInserted(itemCount - 1)
    }

    val objects: List<T?>
        get() = mObjects

    /**
     * Remove all elements from the list.
     */
    fun clear() {
        val size = itemCount
        mObjects.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount(): Int {
        return mObjects.size
    }

    fun getItem(position: Int): T? {
        return mObjects[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    /**
     * Returns the position of the specified item in the array.
     *
     * @param item The item to retrieve the position of.
     * @return The position of the specified item.
     */
    fun getPosition(item: T): Int {
        return mObjects.indexOf(item)
    }

    /**
     * Inserts the specified object at the specified index in the array.
     *
     * @param object The object to insert into the array.
     * @param index  The index at which the object must be inserted.
     */
    fun insert(`object`: T, index: Int) {
        mObjects.add(index, `object`)
        notifyItemInserted(index)
    }

    /**
     * Removes the specified object from the array.
     *
     * @param object The object to remove.
     */
    fun remove(`object`: T) {
        val position = getPosition(`object`)
        mObjects.remove(`object`)
        notifyItemRemoved(position)
    }

    /**
     * Removes the object from specific location.
     *
     * @param location of the object to remove.
     */
    fun remove(location: Int) {
        mObjects.removeAt(location)
        notifyItemRemoved(location)
    }

    fun modify(`object`: T, position: Int) {
        mObjects[position] = `object`
        notifyItemChanged(position)
    }

    fun modify(`object`: T) {
        val pos = mObjects.indexOf(`object`)
        if (pos == -1) return
        mObjects[pos] = `object`
        notifyItemChanged(pos)
    }

    /**
     * Sorts the content of this adapter using the specified comparator.
     *
     * @param comparator The comparator used to sort the objects contained in this adapter.
     */
    fun sort(comparator: Comparator<in T>) {
//        mObjects.sortWith(comparator)
        notifyItemRangeChanged(0, itemCount)
    }

}