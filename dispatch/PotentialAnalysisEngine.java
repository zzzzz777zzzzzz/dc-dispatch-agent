package com.ruoyi.system.dispatch;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.system.dispatch.DispatchModels.PlanItem;
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;
import com.ruoyi.system.domain.PotentialAnalysis;
import com.ruoyi.system.service.IPotentialAnalysisService;

/**
 * 调节潜力分析引擎。
 * <p>
 * 把调度计划的“基线调度”与“优化调度”结果差值为四类潜力指标，替代原先建表时写入的常量：
 * <ul>
 * <li>消纳潜力：优化后新能源消纳电量相对基线的增量，折算为日均功率；</li>
 * <li>转移潜力：购电峰值的削减量；</li>
 * <li>成本优化：全天综合购电成本的节约额；</li>
 * <li>节能潜力：单位用电碳排放的下降率。数据中心总电量由算力需求决定、不随调度改变，
 * 因此以碳排强度而非电量下降表征节能效果。</li>
 * </ul>
 *
 * @author ruoyi
 */
@Service
public class PotentialAnalysisEngine
{
    private static final Logger log = LoggerFactory.getLogger(PotentialAnalysisEngine.class);
    private static final double DT = TelemetrySimulator.STEP_HOURS;
    private static final int HOURS_PER_DAY = 24;

    private final DispatchDao dao;
    private final IPotentialAnalysisService analysisService;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    public PotentialAnalysisEngine(DispatchDao dao, IPotentialAnalysisService analysisService,
            org.springframework.transaction.PlatformTransactionManager txManager)
    {
        this.dao = dao;
        this.analysisService = analysisService;
        this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    /** 单个计划的计算结果 */
    public record PotentialResult(
            double consumptionGainKw,
            double peakCutKw,
            double costSavingYuan,
            double carbonReductionPercent,
            double pvAvailKwh,
            double basePvKwh,
            double optPvKwh,
            int records)
    {
    }

    /**
     * 从指定调度计划回填潜力指标，生成四类分析记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public PotentialResult recompute(long planId)
    {
        PlanRow plan = dao.findPlan(planId);
        if (plan == null)
        {
            throw new IllegalArgumentException("调度计划不存在: " + planId);
        }
        List<PlanItem> items = dao.listItems(planId);
        if (items.isEmpty())
        {
            throw new IllegalStateException("调度计划无明细数据: " + planId);
        }

        double pvAvailKwh = 0;
        double optPvKwh = 0;
        for (PlanItem it : items)
        {
            pvAvailKwh += it.pvAvailKw() * DT;
            optPvKwh += it.pvUsedKw() * DT;
        }
        double baseRate = plan.baseConsumptionRate() == null ? 0 : plan.baseConsumptionRate();
        double basePvKwh = baseRate * pvAvailKwh;
        double gainKwh = optPvKwh - basePvKwh;
        double gainKw = gainKwh / HOURS_PER_DAY;

        double basePeak = plan.basePeakKw() == null ? 0 : plan.basePeakKw();
        double optPeak = plan.optPeakKw() == null ? 0 : plan.optPeakKw();
        double peakCut = Math.max(0, basePeak - optPeak);

        double costSaving = plan.costSaving() == null ? 0 : plan.costSaving();

        double baseEm = plan.baseEmission() == null ? 0 : plan.baseEmission();
        double optEm = plan.optEmission() == null ? 0 : plan.optEmission();
        double carbonRate = baseEm > 0 ? (baseEm - optEm) / baseEm * 100.0 : 0;

        // 重复计算时先清理该计划此前的自动分析记录
        dao.deleteAutoAnalysisByPlan(planId);

        String dcName = plan.dcName() == null ? plan.dcCode() : plan.dcName();
        String date = String.valueOf(plan.planDate());
        int count = 0;

        count += save(planId, "CONSUMPTION", "CONSUMPTION_POTENTIAL",
                dcName + " · " + date + " 新能源消纳潜力",
                dcName, round(gainKw, 2), null, null, null,
                String.format("新能源可用电量 %.1f kWh，优化后消纳 %.1f kWh，较基线 %.1f kWh 增加 %.1f kWh，"
                        + "折算日均功率提升 %.1f kW。", pvAvailKwh, optPvKwh, basePvKwh, gainKwh, gainKw));

        count += save(planId, "TRANSFER", "TRANSFER_POTENTIAL",
                dcName + " · " + date + " 负载转移潜力",
                dcName, null, round(peakCut, 2), null, null,
                String.format("可延迟算力负荷在调度块内重排后，购电峰值由 %.1f kW 降至 %.1f kW，"
                        + "削减 %.1f kW。", basePeak, optPeak, peakCut));

        count += save(planId, "COST", "COST_OPTIMIZATION",
                dcName + " · " + date + " 成本优化潜力",
                dcName, null, null, round(costSaving, 2), null,
                String.format("综合购电成本由 %.2f 元降至 %.2f 元，节约 %.2f 元，降幅 %.2f%%。",
                        plan.baseCost(), plan.optCost(), costSaving,
                        plan.costSavingRate() == null ? 0 : plan.costSavingRate() * 100));

        count += save(planId, "ENERGY", "ENERGY_SAVING",
                dcName + " · " + date + " 节能潜力",
                dcName, null, null, null, round(carbonRate, 2),
                String.format("碳排放由 %.1f kg 降至 %.1f kg，单位用电碳排强度下降 %.2f%%。",
                        baseEm, optEm, carbonRate));

        log.info("潜力分析回填完成 planId={} dc={} 消纳+{}kW 削峰{}kW 节约{}元 碳降{}%",
                planId, plan.dcCode(), round(gainKw, 1), round(peakCut, 1),
                round(costSaving, 1), round(carbonRate, 2));

        return new PotentialResult(round(gainKw, 2), round(peakCut, 2), round(costSaving, 2),
                round(carbonRate, 2), round(pvAvailKwh, 2), round(basePvKwh, 2), round(optPvKwh, 2), count);
    }

    /** 为每个数据中心的最近一份计划回填 */
    public List<Map<String, Object>> recomputeAll()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : dao.dcPlanBreakdown())
        {
            Object pid = row.get("planId");
            if (pid == null)
            {
                continue;
            }
            long planId = ((Number) pid).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("planId", planId);
            item.put("dcCode", row.get("dcCode"));
            try
            {
                // 通过 TransactionTemplate 保证每站独立事务：
                // 直接调用 recompute 会绕过 Spring 代理，@Transactional 不生效
                PotentialResult r = txTemplate.execute(status -> recompute(planId));
                item.put("ok", true);
                item.put("consumptionGainKw", r.consumptionGainKw());
                item.put("peakCutKw", r.peakCutKw());
                item.put("costSavingYuan", r.costSavingYuan());
                item.put("carbonReductionPercent", r.carbonReductionPercent());
            }
            catch (RuntimeException e)
            {
                item.put("ok", false);
                item.put("error", e.getMessage());
            }
            out.add(item);
        }
        return out;
    }

    private int save(long planId, String codeSuffix, String type, String name, String dcName,
            Double consumption, Double transfer, Double cost, Double energyRate, String result)
    {
        PotentialAnalysis pa = new PotentialAnalysis();
        pa.setAnalysisCode("AN" + planId + codeSuffix);
        pa.setAnalysisName(name);
        pa.setAnalysisType(type);
        pa.setAnalysisCycle("DAILY");
        pa.setDataCenters(dcName);
        pa.setConfidence(95);
        pa.setAnalysisDepth("DETAILED");
        pa.setStatus("COMPLETED");
        pa.setConsumptionPotential(consumption == null ? null : Math.round(consumption));
        pa.setTransferPotential(transfer == null ? null : Math.round(transfer));
        pa.setCostSavings(cost == null ? null : Math.round(cost));
        pa.setEnergySavingRate(energyRate);
        pa.setAnalysisResult(result);
        pa.setPlanId(planId);
        pa.setSource("AUTO");
        pa.setCalcTime(new Date());
        pa.setCreateBy("system");
        pa.setRemark("由调度计划自动回填");
        analysisService.insertPotentialAnalysis(pa);
        return 1;
    }

    private static double round(double v, int scale)
    {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
