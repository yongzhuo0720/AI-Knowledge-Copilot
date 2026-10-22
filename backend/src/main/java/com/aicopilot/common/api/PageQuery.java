package com.aicopilot.common.api;

/**
 * 分页请求参数。Controller 收到的是 `?page=0&size=20`，用这个类型统一做**归一化**。
 *
 * <p>为什么要专门包一层，而不是直接把 `int page, int size` 传下去：
 * 分页参数是**外部可控输入**，必须在一个地方把非法值收敛掉，否则每个
 * repository 都要自己防一遍。这里做三件事：
 * <ol>
 *   <li>不传 → 给默认值（前端不用改就能工作）</li>
 *   <li>传负数 → 归零，而不是让 SQL 报错</li>
 *   <li>传超大 size → 压到上限</li>
 * </ol>
 *
 * <p>第 3 条尤其重要：`?size=1000000` 会让数据库一次性把整张表捞进内存，
 * 等于用一个 query string 把「分页」直接打成 DoS。**凡是能控制返回数据量的参数，
 * 都必须有上限**，这是接口设计里很容易漏掉的一条。
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 20;

    /** 单页上限。前端想一次多拿一点可以，但不能拿到「无限」。 */
    public static final int MAX_SIZE = 100;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * 从可能为 null 的请求参数构造（`@RequestParam(required = false) Integer`）。
     * 用包装类型而不是 `int`，是为了区分「没传」和「传了 0」。
     */
    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    /**
     * SQL OFFSET。
     *
     * <p>返回 {@code long} 而不是 {@code int}：`page` 是外部可控的，
     * `page * size` 很容易超出 int 范围（page=100000000 就溢出了），
     * 溢出后变成负数会导致 SQL 直接报错。这里用 long 相乘从根上避免。
     */
    public long offset() {
        return (long) page * size;
    }
}
