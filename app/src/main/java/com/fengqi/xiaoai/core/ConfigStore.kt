package com.fengqi.xiaoai.core

/**
 * 配置存储。
 *
 * 关键设计：**不能使用 DataStore / SharedPreferences 的常规 API**。
 *
 * 原因：Hook 代码运行在「小米运动健康」进程里，而设置页 UI 运行在本模块自己的进程里。
 * 用 Xposed 的 `xposedsharedprefs` 可以跨进程共享，但 DataStore 是进程内单例 + 文件锁，
 * 两个进程同时写会抛 `IllegalStateException: There are multiple DataStores active for the same file`。
 *
 * 因此采用最稳的方案：
 *  - 配置以 **JSON 文件** 形式存放在 App 私有目录 `files/xiaoai_config.json`；
 *  - 内存中维护一份 volatile 快照，读取零开销（Hook 热路径每秒可能读多次）；
 *  - 写入用「临时文件 + rename」保证原子性；
 *  - 通过 **文件 mtime** 做轻量变更探测，实现两个进程之间的实时同步。
 *
 * 这样既不依赖任何框架特性，也不需要跨进程 IPC。
 */
interface ConfigStore {

    /** 当前配置快照（永不返回 null，首次访问会尝试加载，失败返回默认值） */
    fun get(): AiConfig

    /** 覆盖写整份配置 */
    fun set(newConfig: AiConfig)

    /** 局部更新 */
    fun update(block: (AiConfig) -> AiConfig) = set(block(get()))

    /** 比较磁盘 mtime，如果配置被另一个进程改过就重载（轻量，可高频调用） */
    fun reloadIfChanged(): Boolean

    /** 磁盘上配置的最后修改时间（ms），0 表示文件不存在 */
    fun lastModified(): Long
}
