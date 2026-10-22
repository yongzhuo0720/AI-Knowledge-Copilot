package com.aicopilot.common.api;

import java.util.List;

/**
 * 分页响应的统一外壳。
 *
 * <p>为什么不让接口直接返回数组 + 一个 `X-Total-Count` 响应头：
 * 前端要渲染「1-20 / 共 137 条」或者判断「还有没有下一页」，就必须知道总数；
 * 塞在响应头里的话，跨域调试、日志排查、OpenAPI 文档都看不到它，
 * 而且 axios 要单独去读 `response.headers`，很容易被 `.data` 的习惯漏掉。
 * 放进 body 里是更省心的选择。
 *
 * <p>{@code hasNext} 是**冗余字段**（可以由 page/size/totalElements 算出来），
 * 但仍然返回：前端判断「是否显示加载更多」是最高频的操作，让它少算一次就少一处算错。
 * 冗余在这里换来的是「前端不可能算错」，值得。
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static <T> PageResponse<T> of(List<T> items, PageQuery query, long totalElements) {
        int size = query.size();
        // 向上取整的整数写法，避免用 Math.ceil 引入 double 精度问题
        int totalPages = (int) ((totalElements + size - 1) / size);
        // 只看「下一批的起点是否还在总数之内」，刻意**不**用 items.size() 去推。
        // 用 items.size() 推会在「最后一页不满」时算错：137 条、每页 20、page=6 只返回 17 条，
        // offset + 17 = 137 已经不小于 137，看着是对的；但如果调用方传入的 items 更短
        // （比如查询里有过滤），就会得出「还有下一页」而实际没有，
        // 前端于是显示一个点进去永远为空的「加载更多」。
        boolean hasNext = (long) (query.page() + 1) * size < totalElements;
        return new PageResponse<>(items, query.page(), size, totalElements, totalPages, hasNext);
    }

    /** 空页。用于「查了但没有」的场景，避免各处手写 new PageResponse<>(List.of(), ...)。 */
    public static <T> PageResponse<T> empty(PageQuery query) {
        return of(List.of(), query, 0L);
    }
}
