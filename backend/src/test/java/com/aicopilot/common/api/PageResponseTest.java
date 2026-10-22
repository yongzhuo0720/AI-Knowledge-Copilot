package com.aicopilot.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页响应外壳的边界测试。
 *
 * <p>重点在**边界**上：空结果集、正好整除、最后一页不满、页号越界。
 * 这几种情况的 totalPages / hasNext 是最容易算错的，
 * 而一旦算错，前端要么显示一个点不开的「下一页」，要么明明还有数据却不给翻。
 */
class PageResponseTest {

    @Test
    void reportsZeroPagesForAnEmptyResult() {
        PageResponse<String> page = PageResponse.of(List.of(), new PageQuery(0, 20), 0L);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void roundsTotalPagesUp() {
        // 137 条 / 每页 20 → 7 页（第 7 页只有 17 条）
        PageResponse<String> page = PageResponse.of(List.of("a"), new PageQuery(6, 20), 137L);

        assertThat(page.totalPages()).isEqualTo(7);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void hasNextOnEveryPageButTheLast() {
        assertThat(PageResponse.of(List.of("a"), new PageQuery(0, 20), 137L).hasNext()).isTrue();
        assertThat(PageResponse.of(List.of("a"), new PageQuery(5, 20), 137L).hasNext()).isTrue();
        assertThat(PageResponse.of(List.of("a"), new PageQuery(6, 20), 137L).hasNext()).isFalse();
    }

    @Test
    void hasNextDependsOnTotalNotOnHowManyItemsCameBack() {
        // 137 条、每页 20：page=6 是最后一页。
        // 这条用例故意只传 1 个 item（真实情况这里是 17 个），
        // 用来钉住「hasNext 只由 page/size/total 决定」这个性质 ——
        // 一旦有人改成用 items.size() 去算，这里就会红。
        assertThat(PageResponse.of(List.of("a"), new PageQuery(6, 20), 137L).hasNext()).isFalse();
        assertThat(PageResponse.of(List.of(), new PageQuery(6, 20), 137L).hasNext()).isFalse();
    }

    @Test
    void doesNotOverflowWhenTotalDividesEvenly() {
        // 100 条 / 每页 20 → 正好 5 页，第 5 页（page=4）之后不该再有下一页
        PageResponse<String> lastPage = PageResponse.of(List.of("a"), new PageQuery(4, 20), 100L);

        assertThat(lastPage.totalPages()).isEqualTo(5);
        assertThat(lastPage.hasNext()).isFalse();
    }

    @Test
    void reportsNoNextPageWhenTheClientAsksBeyondTheEnd() {
        // 越界页返回空数组，且 hasNext 必须是 false ——
        // 否则前端会卡在「一直点加载更多、一直是空的」的死循环里
        PageResponse<String> beyond = PageResponse.of(List.of(), new PageQuery(99, 20), 37L);

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void emptyIsAConvenienceForNoResults() {
        PageResponse<String> page = PageResponse.empty(new PageQuery(2, 20));

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void echoesBackTheNormalizedPageAndSize() {
        // 客户端传的是 size=1000，响应要回显归一化之后的 100，而不是原样回显 1000，
        // 否则前端会以为自己拿到了 1000 条
        PageResponse<String> page = PageResponse.of(List.of("a"), PageQuery.of(-1, 1000), 1L);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(PageQuery.MAX_SIZE);
    }
}
