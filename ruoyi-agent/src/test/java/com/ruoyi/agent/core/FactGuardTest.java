package com.ruoyi.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 数字守门员的用例：只要有一个数字对不上事实，正文就不能用。
 *
 * @author ruoyi
 */
class FactGuardTest
{
    private static final List<String> FACTS = List.of(
            "全天负荷峰值 873 kW（12:15）",
            "光伏最大出力 600 kW（12:00），全天发电 3992 kWh",
            "成本 7,841 元，基线 8,957 元");

    @Test
    void numbersCopiedFromFactsPass()
    {
        String prose = "北区今天负荷峰值 873 kW，出现在 12:15；光伏全天发电 3992 kWh。";
        assertNull(FactGuard.offendingNumber(prose, FACTS, null));
    }

    @Test
    void roundedOrInventedNumberIsCaught()
    {
        assertEquals("870", FactGuard.offendingNumber("负荷大约 870 kW，比昨天高。", FACTS, null));
        assertEquals("4000", FactGuard.offendingNumber("全天发电约 4000 kWh。", FACTS, null));
    }

    @Test
    void thousandsSeparatorDoesNotMatter()
    {
        assertNull(FactGuard.offendingNumber("成本 7841 元，比基线省了 1116 元。",
                List.of("成本 7,841 元，基线 8,957 元，节约 1,116 元"), null));
    }

    @Test
    void numbersSaidByTheUserAreAllowed()
    {
        assertNull(FactGuard.offendingNumber("目标已改为 20%。",
                List.of("储能 SOC 目标：65% → 20%"), "把储能余量改到 20%"));
    }

    @Test
    void blankProseIsNotFlagged()
    {
        assertNull(FactGuard.offendingNumber("", FACTS, null));
        assertNull(FactGuard.offendingNumber(null, FACTS, null));
    }
}
