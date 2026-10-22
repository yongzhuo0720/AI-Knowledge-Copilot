package com.aicopilot.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数的**归一化**测试。
 *
 * <p>这一组用例看着琐碎，守的却是接口的健壮性：page / size 是外部可控输入，
 * 传负数、传 0、不传、传一个天文数字，都不能让 SQL 报错或者把库拖垮。
 */
class PageQueryTest {

    @Test
    void appliesDefaultsWhenNothingIsProvided() {
        PageQuery query = PageQuery.of(null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.offset()).isZero();
    }

    @Test
    void clampsNegativePageToZero() {
        assertThat(PageQuery.of(-5, 20).page()).isZero();
    }

    @Test
    void treatsNonPositiveSizeAsUnsetRatherThanReturningNothing() {
        // size=0 如果照搬给 SQL，就会变成「每页 0 条」——接口永远返回空数组，
        // 看起来像没有数据，排查起来很费劲。这里直接退回默认值。
        assertThat(PageQuery.of(0, 0).size()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(PageQuery.of(0, -10).size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void capsSizeAtTheMaximum() {
        // 关键防线：`?size=1000000` 不能真的变成「一次捞一百万条」
        assertThat(PageQuery.of(0, 1_000_000).size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(PageQuery.of(0, PageQuery.MAX_SIZE).size()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(PageQuery.of(0, PageQuery.MAX_SIZE - 1).size()).isEqualTo(PageQuery.MAX_SIZE - 1);
    }

    @Test
    void computesOffsetAsALongSoHugePagesCannotOverflow() {
        // page * size 用 int 算会溢出成负数，传给 SQL 直接报错。
        // 这里断言它仍然是个正数，且等于数学上的乘积。
        PageQuery query = PageQuery.of(100_000_000, 100);

        assertThat(query.offset()).isEqualTo(10_000_000_000L);
    }
}
