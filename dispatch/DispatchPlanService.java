package com.ruoyi.system.dispatch;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;
import com.ruoyi.system.dispatch.DispatchModels.OptimizeOutcome;
import com.ruoyi.system.dispatch.DispatchModels.PlanItem;
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;
import com.ruoyi.system.dispatch.DispatchModels.StrategyParams;
import com.ruoyi.system.dispatch.DispatchModels.TaskRow;

/**
 * 调度计划服务：串联历史数据、策略参数、优化器与持久化。
 *
 * @author ruoyi
 */
@Service
public class DispatchPlanService
{
    private static final Logger log = LoggerFactory.getLogger(DispatchPlanService.class);
    private static final DateTimeFormatter CODE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 计划编号后缀计数器，避免同一秒内多次求解产生重复编号 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private final DispatchDao dao;
    private final DispatchOptimizer optimizer;
    private final TelemetrySimulator simulator;

    public DispatchPlanService(DispatchDao dao, DispatchOptimizer optimizer, TelemetrySimulator simulator)
    {
        this.dao = dao;
        this.optimizer = optimizer;
        this.simulator = simulator;
    }

    /**
     * 生成某数据中心某日的调度计划并落库。
     *
     * @param dcCode     数据中心编码，为空时取第一个
     * @param strategyId 调度策略 ID，为空时取启用中的第一个
     * @param date       计划日期，为空时取历史数据的最后一天
     */
    @Transactional(rollbackFor = Exception.class)
    public PlanRow generate(String dcCode, Long strategyId, LocalDate date)
    {
        simulator.ensureHistory(41);

        List<DcInfo> dcs = dao.listDc();
        if (dcs.isEmpty())
        {
            throw new IllegalStateException("尚未配置数据中心档案，请先执行 sql/dispatch_core.sql");
        }
        DcInfo dc = (dcCode == null || dcCode.isBlank()) ? dcs.get(0) : dao.findDc(dcCode);
        if (dc == null)
        {
            throw new IllegalArgumentException("数据中心不存在: " + dcCode);
        }

        LocalDate planDate = date != null ? date : dao.maxHistoryDate();
        if (planDate == null)
        {
            throw new IllegalStateException("尚无历史数据，无法生成计划");
        }
        List<LoadPoint> day = dao.historyOfDay(dc.dcCode(), planDate);
        if (day.size() < TelemetrySimulator.SLOTS_PER_DAY)
        {
            LocalDate latest = dao.maxHistoryDate();
            throw new IllegalStateException(planDate + " 只有 " + day.size() + " 个采样点，凑不齐 96 点没法求解；"
                    + "库里遥测最新到 " + latest + "。先把数据补到 " + planDate
                    + "（首页「一键生成演示数据」），再重跑这份计划。");
        }

        StrategyParams strategy = dao.findStrategy(strategyId);
        DispatchOptimizer.Params params = DispatchOptimizer.Params.defaults();
        if (strategy != null)
        {
            // 策略类型决定目标函数的权重：成本优先 / 消纳优先 / 削峰优先
            params = DispatchOptimizer.Params.ofStrategy(
                    strategy.strategyType(),
                    strategy.socTarget(),
                    strategy.maxConsumptionRatio(),
                    Math.max(50, strategy.transferThreshold()));
        }

        OptimizeOutcome outcome = optimizer.solve(dc, day, params);
        if (outcome.items().isEmpty())
        {
            throw new IllegalStateException("求解未产生调度方案：" + outcome.message());
        }

        double baseCost = outcome.baseCost();
        double optCost = outcome.optCost();
        double saving = baseCost - optCost;
        double savingRate = baseCost > 0 ? saving / baseCost : 0;

        String code = "DP" + dc.dcCode().replace("-", "") + CODE_FMT.format(java.time.LocalDateTime.now())
                + String.format("%03d", SEQ.incrementAndGet() % 1000);
        PlanRow draft = new PlanRow(
                0,
                code,
                dc.dcName() + " · " + planDate + " 调度计划"
                        + (strategy == null ? "" : " · " + strategy.strategyName()),
                strategy == null ? null : (long) strategy.strategyId(),
                dc.dcCode(),
                dc.dcName(),
                planDate,
                15,
                day.size(),
                outcome.status(),
                outcome.solver(),
                outcome.solveMillis(),
                round(outcome.objective(), 2),
                round(baseCost, 2),
                round(optCost, 2),
                round(outcome.baseEmission(), 2),
                round(outcome.optEmission(), 2),
                round(outcome.baseConsumptionRate(), 4),
                round(outcome.optConsumptionRate(), 4),
                round(outcome.basePeakKw(), 2),
                round(outcome.optPeakKw(), 2),
                round(saving, 2),
                round(outcome.baseEmission() - outcome.optEmission(), 2),
                round(savingRate, 4),
                outcome.message() + "｜" + strategySignature(strategy),
                null);

        long planId = dao.insertPlan(draft);
        dao.batchInsertItems(planId, outcome.items());
        log.info("调度计划生成完成 planId={} dc={} 节约={}元 降幅={}%", planId, dc.dcCode(),
                round(saving, 2), round(savingRate * 100, 2));
        return dao.findPlan(planId);
    }

    public List<PlanRow> listPlans(String dcCode, int limit)
    {
        return dao.listPlans(dcCode, limit <= 0 ? 20 : Math.min(limit, 200));
    }

    public PlanRow findPlan(long planId)
    {
        return dao.findPlan(planId);
    }

    public List<PlanItem> listItems(long planId)
    {
        return dao.listItems(planId);
    }

    /**
     * 策略签名：类型 + 三个关键参数。
     * <p>
     * 写进计划备注，用来判断「同样条件下是不是刚算过」——只比策略 id 不够，
     * 改了 SOC 目标再重跑，参数不同结果就不同，那不是重复计算。
     */
    public static String strategySignature(com.ruoyi.system.dispatch.DispatchModels.StrategyParams s)
    {
        if (s == null)
        {
            return "默认参数";
        }
        return "策略 " + s.strategyCode() + "（" + s.strategyType() + "）"
                + "｜SOC " + Math.round(s.socTarget() * 100) + "%"
                + "｜消纳 " + Math.round(s.maxConsumptionRatio() * 100) + "%"
                + "｜阈值 " + Math.round(s.transferThreshold()) + " kW";
    }

    /**
     * 为全部数据中心各生成一份计划（批量复盘）。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<PlanRow> generateAll(Long strategyId, LocalDate date)
    {
        List<PlanRow> rows = new ArrayList<>();
        for (DcInfo dc : dao.listDc())
        {
            try
            {
                rows.add(generate(dc.dcCode(), strategyId, date));
            }
            catch (RuntimeException e)
            {
                log.warn("数据中心 {} 计划生成失败: {}", dc.dcCode(), e.getMessage());
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ 看板

    /**
     * 运行看板数据：装机规模、累计优化效益、最新计划与预测精度。
     */
    public Map<String, Object> dashboard()
    {
        simulator.ensureHistory(41);
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> fleet = dao.fleetCapacity();
        out.put("dcCount", fleet.get("dc_count"));
        out.put("itCapacityKw", fleet.get("it_kw"));
        out.put("pvCapacityKw", fleet.get("pv_kw"));
        out.put("storageKwh", fleet.get("storage_kwh"));
        out.put("gasKw", fleet.get("gas_kw"));

        Map<String, Object> summary = dao.planSummary(null);
        out.put("planCount", summary.get("plans"));
        out.put("totalSaving", summary.get("saving"));
        out.put("totalEmissionReduction", summary.get("emission"));
        out.put("avgSavingRate", summary.get("rate"));

        Object latestId = summary.get("latest");
        PlanRow latest = null;
        if (latestId != null && !"0".equals(String.valueOf(latestId)))
        {
            latest = dao.findPlan(((Number) latestId).longValue());
        }
        if (latest != null)
        {
            Map<String, Object> lp = new LinkedHashMap<>();
            lp.put("planId", latest.planId());
            lp.put("planName", latest.planName());
            lp.put("dcCode", latest.dcCode());
            lp.put("dcName", latest.dcName());
            lp.put("planDate", String.valueOf(latest.planDate()));
            lp.put("baseCost", latest.baseCost());
            lp.put("optCost", latest.optCost());
            lp.put("costSaving", latest.costSaving());
            lp.put("costSavingRate", latest.costSavingRate());
            lp.put("baseEmission", latest.baseEmission());
            lp.put("optEmission", latest.optEmission());
            lp.put("basePeakKw", latest.basePeakKw());
            lp.put("optPeakKw", latest.optPeakKw());
            lp.put("baseConsumptionRate", latest.baseConsumptionRate());
            lp.put("optConsumptionRate", latest.optConsumptionRate());
            lp.put("solver", latest.solver());
            lp.put("solveMillis", latest.solveMillis());
            out.put("latestPlan", lp);

            List<Map<String, Object>> curve = new ArrayList<>();
            for (PlanItem it : dao.listItems(latest.planId()))
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", it.ts().format(DateTimeFormatter.ofPattern("HH:mm")));
                row.put("load", round(it.loadKw(), 1));
                row.put("pv", round(it.pvUsedKw(), 1));
                row.put("grid", round(it.gridKw(), 1));
                row.put("charge", round(it.storageChargeKw(), 1));
                row.put("discharge", round(it.storageDischargeKw(), 1));
                row.put("gas", round(it.gasKw(), 1));
                row.put("soc", round(it.soc() * 100, 2));
                row.put("price", it.price());
                curve.add(row);
            }
            out.put("curve", curve);
        }

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Map<String, Object> row : dao.dcPlanBreakdown())
        {
            Map<String, Object> item = new LinkedHashMap<>(row);
            breakdown.add(item);
        }
        out.put("dcBreakdown", breakdown);

        if (latest != null && latest.optCost() != null && latest.baseCost() != null)
        {
            out.put("latestSaving", round(latest.baseCost() - latest.optCost(), 2));
        }
        else
        {
            out.put("latestSaving", 0);
        }

        out.put("historyRange", dao.historyRange());
        out.put("solverReady", optimizer.isNativeReady());
        out.put("solverError", optimizer.nativeError());

        TaskRow task = dao.latestCompletedTask();
        if (task != null)
        {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("taskId", task.taskId());
            t.put("taskName", task.taskName());
            t.put("dcName", task.dcName());
            t.put("modelName", task.modelName());
            t.put("mape", task.mape());
            t.put("mae", task.mae());
            out.put("latestForecast", t);
        }
        return out;
    }

    private static double round(double v, int scale)
    {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
