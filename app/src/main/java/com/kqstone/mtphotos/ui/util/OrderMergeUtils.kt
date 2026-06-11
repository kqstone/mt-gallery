package com.kqstone.mtphotos.ui.util

object OrderMergeUtils {
    /**
     * 合并服务端原始列表与本地保存的拖拽顺序。
     * 使用前置锚点合并算法：
     * 1. 服务端新增的项目，会被锚定在它们前一个“已知项目”的后面。
     * 2. 然后按照本地保存的顺序放置“已知项目”，其对应锚定的新增项会紧随其后。
     * 3. 在所有已知项目前面的新增项会被放置在整个列表的最前方。
     * 
     * @param originalList 服务端返回的原始列表（已按数量排序）
     * @param savedOrder 本地保存的 ID 列表
     * @param idSelector 从项目中提取 ID 的函数
     */
    fun <T> mergeOrder(
        originalList: List<T>,
        savedOrder: List<String>,
        idSelector: (T) -> String
    ): List<T> {
        if (savedOrder.isEmpty()) return originalList

        val savedOrderSet = savedOrder.toSet()
        
        // 记录每个已知项目后面紧跟的新增项目
        // key: 锚点已知项目的 ID (null 表示排在最前面的新增项)
        // value: 依附于该锚点的新增项目列表
        val newItemsByAnchor = mutableMapOf<String?, MutableList<T>>()
        
        var currentAnchor: String? = null
        val knownItemsMap = mutableMapOf<String, T>()

        for (item in originalList) {
            val id = idSelector(item)
            if (id in savedOrderSet) {
                currentAnchor = id
                knownItemsMap[id] = item
            } else {
                newItemsByAnchor.getOrPut(currentAnchor) { mutableListOf() }.add(item)
            }
        }

        val result = mutableListOf<T>()
        
        // 1. 放入没有锚点（排在最前面）的新增项
        newItemsByAnchor[null]?.let { result.addAll(it) }

        // 2. 按本地保存顺序放置已知项及其附带的新增项
        for (savedId in savedOrder) {
            val knownItem = knownItemsMap[savedId]
            if (knownItem != null) {
                result.add(knownItem)
                newItemsByAnchor[savedId]?.let { result.addAll(it) }
            }
        }

        return result
    }
}
