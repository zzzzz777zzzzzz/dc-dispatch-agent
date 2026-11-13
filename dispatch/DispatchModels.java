package com.ruoyi.system.dispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度域数据模型。
 * <p>
 * 采用不可变 record 表达只读数据视图，便于在 DAO、求解器与服务之间传递，
 * 同时避免大量样板 getter/setter。
 *
 * @author ruoyi
 */
public final class DispatchModels
{
    private DispatchModels()
    {
    }

    /** 数据中心档案：求解器所需的全部物理参数 */
    public record DcInfo(
            String dcCode,
            String dcName,
            String region,
            double itCapacityKw,
            double baseLoadKw,
            double deferrableRatio,
            double pvCapacityKw,
            double storageCapacityKwh,
            double storagePowerKw,
            double socMin,
            double socMax,
            double socInit,
            double effCharge,
            double effDischarge,
            double gasCapacityKw,
            double gasRampKwPerMin,
            double gridCapacityKw)
    {
    }

    /** 单个时间点的负荷、新能源可用出力与电价 */
    public record LoadPoint(LocalDateTime ts, double loadKw, double pvAvailKw, double price)
    {
    }

    /** 待写入历史表的采样点 */
    public record HistorySlot(
            String dcCode,
            LocalDateTime ts,
            double loadKw,
            double pvAvailKw,
            double price,
            double temp,
            boolean workday)
    {
    }

    /** 调度计划中的一个时段 */
    public record PlanItem(
            int seq,
            LocalDateTime ts,
            double loadKw,
            double rigidLoadKw,
            double shiftLoadKw,
            double pvAvailKw,
            double pvUsedKw,
            double gridKw,
            double storageChargeKw,
            double storageDischargeKw,
            double gasKw,
            double soc,
            double price,
            double emissionKg,
            double costYuan)
    {
    }

    /** 调度计划主记录 */
    public record PlanRow(
            long planId,
            String planCode,
            String planName,
            Long strategyId,
            String dcCode,
            String dcName,
            LocalDate planDate,
            int stepMinutes,
            int periodCount,
            String status,
            String solver,
            Long solveMillis,
            Double objectiveValue,
            Double baseCost,
            Double optCost,
            Double baseEmission,
            Double optEmission,
            Double baseConsumptionRate,
            Double optConsumptionRate,
            Double basePeakKw,
            Double optPeakKw,
            Double costSaving,
            Double emissionReduction,
            Double costSavingRate,
            String remark,
            LocalDateTime createTime)
    {
    }

    /** 预测任务记录 */
    public record TaskRow(
            long taskId,
            String taskCode,
            String taskName,
            String dcCode,
            String dcName,
            String modelCode,
            String modelName,
            int horizon,
            String status,
            LocalDateTime trainStart,
            LocalDateTime trainEnd,
            LocalDateTime validStart,
            LocalDateTime execTime,
            Long execMillis,
            Double mae,
            Double rmse,
            Double mape,
            Integer sampleCount,
            String remark,
            LocalDateTime createTime)
    {
    }

    /** 预测结果中的一个点 */
    public record ForecastPoint(int seq, LocalDateTime ts, Double actualKw, Double predictKw)
    {
    }

    /** 模型台账记录 */
    public record ModelRow(
            long modelId,
            String modelCode,
            String modelName,
            String modelType,
            String status,
            String version,
            String dcCode,
            LocalDateTime trainTime,
            Double mae,
            Double rmse,
            Double mape,
            Integer trainSamples,
            Integer validSamples,
            String params,
            String description)
    {
    }

    /** 预测精度指标 */
    public record Metric(double mae, double rmse, double mape, int samples)
    {
    }

    /** 调度策略中与求解相关的参数 */
    public record StrategyParams(
            long strategyId,
            String strategyName,
            String strategyCode,
            String strategyType,
            double socTarget,
            double maxConsumptionRatio,
            double transferThreshold,
            int status)
    {
    }

    /** 求解器输出 */
    public record OptimizeOutcome(
            String status,
            String message,
            String solver,
            long solveMillis,
            double objective,
            double baseCost,
            double optCost,
            double baseEmission,
            double optEmission,
            double baseConsumptionRate,
            double optConsumptionRate,
            double basePeakKw,
            double optPeakKw,
            List<PlanItem> items)
    {
    }
}
