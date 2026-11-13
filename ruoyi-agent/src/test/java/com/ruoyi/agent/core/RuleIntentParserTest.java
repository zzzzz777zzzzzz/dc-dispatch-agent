package com.ruoyi.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 规则解析器用例：覆盖方案 3.1 里那几句示例话术。
 *
 * @author ruoyi
 */
class RuleIntentParserTest
{
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    private RuleIntentParser parser;

    @BeforeEach
    void setUp()
    {
        parser = new RuleIntentParser(() -> TODAY);
    }

    @Test
    void reportWithStationAndRelativeDate()
    {
        Intent i = parser.parse("北区A昨天省了多少钱");
        assertEquals(Action.QUERY_REPORT, i.action());
        assertEquals("DC-A", i.dcCode());
        assertEquals("2026-11-10", i.date());
    }

    @Test
    void solveWithTomorrow()
    {
        Intent i = parser.parse("重跑明天北区A的计划");
        assertEquals(Action.SOLVE_PLAN, i.action());
        assertEquals("DC-A", i.dcCode());
        assertEquals("2026-11-12", i.date());
    }

    @Test
    void statusOfAnotherStation()
    {
        Intent i = parser.parse("现在南区什么状态");
        assertEquals(Action.QUERY_STATUS, i.action());
        assertEquals("DC-B", i.dcCode());
    }

    @Test
    void planListWithLimit()
    {
        Intent i = parser.parse("看看最近3份计划");
        assertEquals(Action.QUERY_PLAN, i.action());
        assertEquals(3, i.limit(5));
    }

    @Test
    void planDetailById()
    {
        Intent i = parser.parse("计划 12 的明细");
        assertEquals(Action.QUERY_PLAN_DETAIL, i.action());
        assertNotNull(i.planId());
        assertEquals(12L, i.planId());
    }

    @Test
    void explain()
    {
        Intent i = parser.parse("为什么建议少充");
        assertEquals(Action.EXPLAIN, i.action());
    }

    @Test
    void help()
    {
        Intent i = parser.parse("你能做什么");
        assertEquals(Action.HELP, i.action());
    }

    @Test
    void unknownFallsBack()
    {
        Intent i = parser.parse("今天天气不错");
        assertEquals(Action.UNKNOWN, i.action());
    }

    @Test
    void undoIsDistinctFromCancel()
    {
        assertEquals(Action.UNDO, parser.parse("撤销刚才那版").action());
        assertEquals(Action.CANCEL, parser.parse("取消").action());
    }

    @Test
    void identityIsSmalltalkNotHelp()
    {
        assertEquals(Action.SMALLTALK, parser.parse("你是谁").action());
        assertEquals(Action.SMALLTALK, parser.parse("介绍一下你自己").action());
        assertEquals(Action.HELP, parser.parse("你能做什么").action());
    }

    @Test
    void strategyChangeCarriesTarget()
    {
        Intent i = parser.parse("把储能余量改到 20%");
        assertEquals(Action.UPDATE_STRATEGY, i.action());
        assertEquals(20.0, i.slots().get("socTarget"));
    }

    @Test
    void strategySwitchIsNotAStrategyParamChange()
    {
        assertEquals(Action.SELECT_STRATEGY, parser.parse("换成成本优先的策略").action());
        assertEquals(Action.SELECT_STRATEGY, parser.parse("切到削峰那条策略").action());
        assertEquals(Action.SELECT_STRATEGY, parser.parse("启用新能源优先策略").action());
        // 改参数不能误判成切换
        assertEquals(Action.UPDATE_STRATEGY, parser.parse("把策略的储能余量设成 30%").action());
    }

    @Test
    void strategySwitchCarriesCode()
    {
        Intent i = parser.parse("切到 STR003 这条策略");
        assertEquals(Action.SELECT_STRATEGY, i.action());
        assertEquals("STR003", i.slots().get("strategyCode"));
        assertNotNull(i.slots().get("strategyName"));
    }

    @Test
    void strategyQuestionIsAReadNotASelect()
    {
        assertEquals(Action.QUERY_STRATEGY, parser.parse("现在用的是哪条策略").action());
        assertEquals(Action.QUERY_STRATEGY, parser.parse("有哪些调度策略").action());
        // 带动作词的仍然是切换
        assertEquals(Action.SELECT_STRATEGY, parser.parse("切换到现在省钱的策略").action());
    }


    @Test
    void strategySwitchBeatsSolvePlanWhenBothWordsAppear()
    {
        // 「换策略再重跑」先当切换，重跑是下一步
        assertEquals(Action.SELECT_STRATEGY, parser.parse("把策略换成成本优化").action());
    }

    @Test
    void followUpSwitchesStationOnly()
    {
        Intent first = parser.parse("北区A现在什么状态");
        Intent second = parser.parse("那南区呢", first);
        assertEquals(Action.QUERY_STATUS, second.action());
        assertEquals("DC-B", second.dcCode());
    }

    @Test
    void followUpKeepsStationAndChangesDate()
    {
        Intent first = parser.parse("北区A现在什么状态");
        Intent second = parser.parse("昨天呢", first);
        assertEquals(Action.QUERY_STATUS, second.action());
        assertEquals("DC-A", second.dcCode());
        assertEquals("2026-11-10", second.date());
        assertEquals(2, second.assumptions().size(), "应当同时记录动作与站点的沿用");
    }
}
