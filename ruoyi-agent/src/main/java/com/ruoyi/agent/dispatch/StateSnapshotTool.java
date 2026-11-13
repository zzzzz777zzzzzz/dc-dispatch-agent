package com.ruoyi.agent.dispatch;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.agent.core.AgentContext;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.SideEffect;
import com.ruoyi.agent.core.ToolResult;
import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;

/**
 * 读当前状态：最近一个采样点的负荷、光伏、电价，以及站点装机。
 *
 * @author ruoyi
 */
public class StateSnapshotTool implements AgentTool
{
    private final DispatchDao dao;

    public StateSnapshotTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "state.snapshot";
    }

    @Override
    public String title()
    {
        return "查当前状态";
    }

    @Override
    public String description()
    {
        return "读某个数据中心最近一个采样点的负荷、光伏出力、电价和装机规模";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.READ;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        String code = args == null ? null : (String) args.get("dcCode");
        LocalDate asked = args == null ? null
                : DispatchToolSupport.parseDate(String.valueOf(args.get("date")));
        DcInfo dc = DispatchToolSupport.resolveDc(dao, code);
        if (dc == null)
        {
            return ToolResult.fail("还没有配置数据中心档案，请先执行 sql/dispatch_core.sql。");
        }
        LocalDate latest = dao.maxHistoryDate();
        if (latest == null)
        {
            return ToolResult.fail("历史表是空的，还没有可用的遥测数据。");
        }
        // 问哪一天就查哪一天：没指定才回落到最近一天
        LocalDate day = asked != null ? asked : latest;
        List<LoadPoint> points = dao.historyOfDay(dc.dcCode(), day);
        if (points.isEmpty())
        {
            return ToolResult.fail(noDataMessage(dc, day));
        }
        LoadPoint last = points.get(points.size() - 1);
        LoadPoint loadPeak = points.get(0);
        LoadPoint pvPeak = points.get(0);
        double pvEnergy = 0;
        double priceMin = points.get(0).price();
        double priceMax = priceMin;
        for (LoadPoint p : points)
        {
            if (p.loadKw() > loadPeak.loadKw())
            {
                loadPeak = p;
            }
            if (p.pvAvailKw() > pvPeak.pvAvailKw())
            {
                pvPeak = p;
            }
            pvEnergy += p.pvAvailKw() * 0.25;
            priceMin = Math.min(priceMin, p.price());
            priceMax = Math.max(priceMax, p.price());
        }

        List<String> facts = new ArrayList<>();
        facts.add("站点：" + dc.dcName() + "（" + dc.dcCode() + "）");
        facts.add("日期：" + day + (asked == null ? "（库里最近一天有数据的日子）" : "")
                + "，共 " + points.size() + " 个 15 分钟采样点");
        facts.add("最新一点 " + hhmm(last.ts()) + "：负荷 " + DispatchToolSupport.kw(last.loadKw())
                + "，光伏 " + DispatchToolSupport.kw(last.pvAvailKw())
                + "，电价 " + DispatchToolSupport.priceOf(last));
        if (points.size() > 1)
        {
            facts.add("全天负荷峰值 " + DispatchToolSupport.kw(loadPeak.loadKw())
                    + "（" + hhmm(loadPeak.ts()) + "）");
            facts.add("光伏最大出力 " + DispatchToolSupport.kw(pvPeak.pvAvailKw())
                    + "（" + hhmm(pvPeak.ts()) + "），全天发电 "
                    + DispatchToolSupport.num(pvEnergy, 0) + " kWh");
            facts.add("电价区间 " + String.format(java.util.Locale.CHINA, "%.2f ~ %.2f 元/度",
                    priceMin, priceMax));
        }
        facts.add("装机：IT " + DispatchToolSupport.kw(dc.itCapacityKw())
                + "｜光伏 " + DispatchToolSupport.kw(dc.pvCapacityKw())
                + "｜储能 " + DispatchToolSupport.num(dc.storageCapacityKwh(), 0) + " kWh / "
                + DispatchToolSupport.kw(dc.storagePowerKw())
                + "｜燃机 " + DispatchToolSupport.kw(dc.gasCapacityKw()));

        Map<String, Object> data = new LinkedHashMap<>();
        // 数据新鲜度：不是今天的数据就明说，别让人以为这块表是实时的
        long days = java.time.temporal.ChronoUnit.DAYS.between(day, LocalDate.now());
        if (days > 0)
        {
            facts.add("提示：这是 " + days + " 天前的数据，不是实时遥测；库里最新只到 " + latest + "。");
            data.put("staleDays", days);
        }
        data.put("dcCode", dc.dcCode());
        data.put("date", day.toString());
        data.put("ts", last.ts().toString());
        data.put("loadKw", last.loadKw());
        data.put("pvKw", last.pvAvailKw());
        data.put("price", last.price());
        data.put("loadPeakKw", loadPeak.loadKw());
        data.put("pvEnergyKwh", pvEnergy);
        return ToolResult.ok(dc.dcName() + " · " + day + " 状态", facts, data);
    }

    /** 取时点的 HH:mm，用于「峰值出现在几点」这种说明 */
    private static String hhmm(java.time.LocalDateTime ts)
    {
        return String.format(java.util.Locale.CHINA, "%02d:%02d", ts.getHour(), ts.getMinute());
    }

    /**
     * 指定日期没有数据时的回话：说清库里到底有什么，再给两条可执行的下一步。
     * <p>
     * 不能默默把最近一天的数据当成「今天」交上去——那是最容易被当成系统在瞎编的地方。
     */
    private String noDataMessage(DcInfo dc, LocalDate day)
    {
        LocalDate first = dao.minHistoryDate();
        LocalDate lastOfDc = dao.maxHistoryDateOf(dc.dcCode());
        StringBuilder sb = new StringBuilder();
        sb.append("没有 ").append(day).append(" 的数据。");
        if (lastOfDc == null)
        {
            sb.append(dc.dcName()).append(" 在库里一条遥测都没有，先用首页的「一键生成演示数据」把数据建起来。");
            return sb.toString();
        }
        sb.append(dc.dcName()).append(" 的遥测只覆盖 ").append(first).append(" ~ ")
                .append(lastOfDc).append("（最近一条 ").append(lastOfDc).append("）");
        if (day.isAfter(LocalDate.now()))
        {
            sb.append("。").append(day).append(" 还没到，未来的日期不会有遥测。");
        }
        else
        {
            long gap = java.time.temporal.ChronoUnit.DAYS.between(lastOfDc, day);
            if (gap > 0)
            {
                sb.append("，比你问的这天早 ").append(gap).append(" 天");
            }
            sb.append("。要今天或更近的数据，点首页的「一键生成演示数据」把遥测补齐再问。");
        }
        sb.append("也可以直接说“看 ").append(lastOfDc).append(" 的状态”。");
        return sb.toString();
    }
}
