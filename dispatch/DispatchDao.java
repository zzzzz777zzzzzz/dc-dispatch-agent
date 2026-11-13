package com.ruoyi.system.dispatch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.ForecastPoint;
import com.ruoyi.system.dispatch.DispatchModels.HistorySlot;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;
import com.ruoyi.system.dispatch.DispatchModels.ModelRow;
import com.ruoyi.system.dispatch.DispatchModels.PlanItem;
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;
import com.ruoyi.system.dispatch.DispatchModels.TaskRow;

/**
 * 调度域数据访问。
 * <p>
 * 分析型模块以 JdbcTemplate 直接访问，避免为批量时序数据维护大量 MyBatis 映射；
 * 写操作均为参数化语句，不做字符串拼接。
 *
 * @author ruoyi
 */
@Repository
public class DispatchDao
{
    private final JdbcTemplate jdbc;

    public DispatchDao(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 数据中心

    private static final RowMapper<DcInfo> DC_MAPPER = (rs, i) -> new DcInfo(
            rs.getString("dc_code"),
            rs.getString("dc_name"),
            rs.getString("region"),
            rs.getDouble("it_capacity_kw"),
            rs.getDouble("base_load_kw"),
            rs.getDouble("deferrable_ratio"),
            rs.getDouble("pv_capacity_kw"),
            rs.getDouble("storage_capacity_kwh"),
            rs.getDouble("storage_power_kw"),
            rs.getDouble("storage_soc_min"),
            rs.getDouble("storage_soc_max"),
            rs.getDouble("storage_soc_init"),
            rs.getDouble("storage_eff_charge"),
            rs.getDouble("storage_eff_discharge"),
            rs.getDouble("gas_capacity_kw"),
            rs.getDouble("gas_ramp_kw_per_min"),
            rs.getDouble("grid_capacity_kw"));

    public List<DcInfo> listDc()
    {
        return jdbc.query("select * from dc_info where status = '0' order by dc_code", DC_MAPPER);
    }

    public DcInfo findDc(String dcCode)
    {
        List<DcInfo> list = jdbc.query("select * from dc_info where dc_code = ?", DC_MAPPER, dcCode);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------ 历史数据

    public long historyCount()
    {
        Long n = jdbc.queryForObject("select count(*) from dc_load_history", Long.class);
        return n == null ? 0L : n;
    }

    public LocalDate maxHistoryDate()
    {
        return jdbc.query("select max(ts) as m from dc_load_history", (rs, i) -> {
            Timestamp t = rs.getTimestamp("m");
            return t == null ? null : t.toLocalDateTime().toLocalDate();
        }).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** 历史数据最早的一天；整表为空时返回 null */
    public LocalDate minHistoryDate()
    {
        return jdbc.query("select min(ts) as m from dc_load_history", (rs, i) -> {
            Timestamp t = rs.getTimestamp("m");
            return t == null ? null : t.toLocalDateTime().toLocalDate();
        }).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** 某个数据中心最近一天有数据的日期；该站点没有数据时返回 null */
    public LocalDate maxHistoryDateOf(String dcCode)
    {
        return jdbc.query("select max(ts) as m from dc_load_history where dc_code = ?", (rs, i) -> {
            Timestamp t = rs.getTimestamp("m");
            return t == null ? null : t.toLocalDateTime().toLocalDate();
        }, dcCode).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public List<LoadPoint> history(String dcCode, LocalDateTime from, LocalDateTime to)
    {
        return jdbc.query(
                "select ts, load_kw, pv_avail_kw, price from dc_load_history "
                        + "where dc_code = ? and ts >= ? and ts < ? order by ts",
                (rs, i) -> new LoadPoint(
                        rs.getTimestamp("ts").toLocalDateTime(),
                        rs.getDouble("load_kw"),
                        rs.getDouble("pv_avail_kw"),
                        rs.getDouble("price")),
                dcCode, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    public List<LoadPoint> historyOfDay(String dcCode, LocalDate date)
    {
        return history(dcCode, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    public void batchInsertHistory(List<HistorySlot> slots)
    {
        batchInsertHistory(slots, true);
    }

    /**
     * 批量写入历史采样点。
     *
     * @param upsert 为 true 时按 (dc_code, ts) 覆盖写入；首次生成数据时置 false 以走纯插入，速度更快
     */
    public void batchInsertHistory(List<HistorySlot> slots, boolean upsert)
    {
        if (slots == null || slots.isEmpty())
        {
            return;
        }
        String sql = "insert into dc_load_history(dc_code, ts, load_kw, pv_avail_kw, price, outdoor_temp, workday) "
                + "values(?,?,?,?,?,?,?)"
                + (upsert
                        ? " on duplicate key update load_kw = values(load_kw), pv_avail_kw = values(pv_avail_kw), "
                                + "price = values(price), outdoor_temp = values(outdoor_temp)"
                        : "");
        jdbc.batchUpdate(sql, slots, 2000, (ps, s) -> {
            ps.setString(1, s.dcCode());
            ps.setTimestamp(2, Timestamp.valueOf(s.ts()));
            ps.setDouble(3, s.loadKw());
            ps.setDouble(4, s.pvAvailKw());
            ps.setDouble(5, s.price());
            ps.setDouble(6, s.temp());
            ps.setBoolean(7, s.workday());
        });
    }

    // ------------------------------------------------------------------ 调度计划

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, i) -> new PlanRow(
            rs.getLong("plan_id"),
            rs.getString("plan_code"),
            rs.getString("plan_name"),
            (Long) rs.getObject("strategy_id", Long.class),
            rs.getString("dc_code"),
            rs.getString("dc_name"),
            rs.getDate("plan_date") == null ? null : rs.getDate("plan_date").toLocalDate(),
            rs.getInt("step_minutes"),
            rs.getInt("period_count"),
            rs.getString("status"),
            rs.getString("solver"),
            (Long) rs.getObject("solve_millis", Long.class),
            (Double) rs.getObject("objective_value", Double.class),
            (Double) rs.getObject("base_cost", Double.class),
            (Double) rs.getObject("opt_cost", Double.class),
            (Double) rs.getObject("base_emission", Double.class),
            (Double) rs.getObject("opt_emission", Double.class),
            (Double) rs.getObject("base_consumption_rate", Double.class),
            (Double) rs.getObject("opt_consumption_rate", Double.class),
            (Double) rs.getObject("base_peak_kw", Double.class),
            (Double) rs.getObject("opt_peak_kw", Double.class),
            (Double) rs.getObject("cost_saving", Double.class),
            (Double) rs.getObject("emission_reduction", Double.class),
            (Double) rs.getObject("cost_saving_rate", Double.class),
            rs.getString("remark"),
            toLocal(rs, "create_time"));

    private static LocalDateTime toLocal(ResultSet rs, String col) throws SQLException
    {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static final String PLAN_SELECT = "select p.*, d.dc_name from dispatch_plan p "
            + "left join dc_info d on d.dc_code = p.dc_code ";

    public long insertPlan(PlanRow r)
    {
        jdbc.update(
                "insert into dispatch_plan(plan_code, plan_name, strategy_id, dc_code, plan_date, step_minutes, "
                        + "period_count, status, solver, solve_millis, objective_value, base_cost, opt_cost, "
                        + "base_emission, opt_emission, base_consumption_rate, opt_consumption_rate, base_peak_kw, "
                        + "opt_peak_kw, cost_saving, emission_reduction, cost_saving_rate, remark, create_by, create_time) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                r.planCode(), r.planName(), r.strategyId(), r.dcCode(),
                java.sql.Date.valueOf(r.planDate()), r.stepMinutes(), r.periodCount(), r.status(),
                r.solver(), r.solveMillis(), r.objectiveValue(), r.baseCost(), r.optCost(),
                r.baseEmission(), r.optEmission(), r.baseConsumptionRate(), r.optConsumptionRate(),
                r.basePeakKw(), r.optPeakKw(), r.costSaving(), r.emissionReduction(), r.costSavingRate(),
                r.remark(), "admin");
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return id == null ? -1L : id;
    }

    public void batchInsertItems(long planId, List<PlanItem> items)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }
        jdbc.batchUpdate(
                "insert into dispatch_plan_item(plan_id, seq, ts, load_kw, rigid_load_kw, shift_load_kw, "
                        + "pv_avail_kw, pv_used_kw, grid_kw, storage_charge_kw, storage_discharge_kw, gas_kw, "
                        + "soc, price, emission_kg, cost_yuan) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                items,
                200,
                (ps, it) -> {
                    int i = 1;
                    ps.setLong(i++, planId);
                    ps.setInt(i++, it.seq());
                    ps.setTimestamp(i++, Timestamp.valueOf(it.ts()));
                    ps.setDouble(i++, it.loadKw());
                    ps.setDouble(i++, it.rigidLoadKw());
                    ps.setDouble(i++, it.shiftLoadKw());
                    ps.setDouble(i++, it.pvAvailKw());
                    ps.setDouble(i++, it.pvUsedKw());
                    ps.setDouble(i++, it.gridKw());
                    ps.setDouble(i++, it.storageChargeKw());
                    ps.setDouble(i++, it.storageDischargeKw());
                    ps.setDouble(i++, it.gasKw());
                    ps.setDouble(i++, it.soc());
                    ps.setDouble(i++, it.price());
                    ps.setDouble(i++, it.emissionKg());
                    ps.setDouble(i++, it.costYuan());
                });
    }

    public List<PlanRow> listPlans(String dcCode, int limit)
    {
        String sql = PLAN_SELECT + (dcCode == null || dcCode.isBlank() ? "" : "where p.dc_code = ? ")
                + "order by p.plan_id desc limit ?";
        return dcCode == null || dcCode.isBlank()
                ? jdbc.query(sql, PLAN_MAPPER, limit)
                : jdbc.query(sql, PLAN_MAPPER, dcCode, limit);
    }

    public PlanRow findPlan(long planId)
    {
        List<PlanRow> list = jdbc.query(PLAN_SELECT + "where p.plan_id = ?", PLAN_MAPPER, planId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 某个数据中心某一天的计划；同一天求解过多次时取最新的一份 */
    public PlanRow findPlanByDate(String dcCode, LocalDate date)
    {
        List<PlanRow> list = jdbc.query(
                PLAN_SELECT + "where p.dc_code = ? and p.plan_date = ? order by p.plan_id desc limit 1",
                PLAN_MAPPER, dcCode, java.sql.Date.valueOf(date));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 某个数据中心最近一份计划的日期；没有计划时返回 null */
    public LocalDate maxPlanDateOf(String dcCode)
    {
        return jdbc.query("select max(plan_date) as m from dispatch_plan where dc_code = ?", (rs, i) -> {
            java.sql.Date d = rs.getDate("m");
            return d == null ? null : d.toLocalDate();
        }, dcCode).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static final RowMapper<PlanItem> ITEM_MAPPER = (rs, i) -> new PlanItem(
            rs.getInt("seq"),
            rs.getTimestamp("ts").toLocalDateTime(),
            rs.getDouble("load_kw"),
            rs.getDouble("rigid_load_kw"),
            rs.getDouble("shift_load_kw"),
            rs.getDouble("pv_avail_kw"),
            rs.getDouble("pv_used_kw"),
            rs.getDouble("grid_kw"),
            rs.getDouble("storage_charge_kw"),
            rs.getDouble("storage_discharge_kw"),
            rs.getDouble("gas_kw"),
            rs.getDouble("soc"),
            rs.getDouble("price"),
            rs.getDouble("emission_kg"),
            rs.getDouble("cost_yuan"));

    public List<PlanItem> listItems(long planId)
    {
        return jdbc.query("select * from dispatch_plan_item where plan_id = ? order by seq", ITEM_MAPPER, planId);
    }

    /** 删除计划及其明细 */
    public int deletePlan(long planId)
    {
        jdbc.update("delete from dispatch_plan_item where plan_id = ?", planId);
        return jdbc.update("delete from dispatch_plan where plan_id = ?", planId);
    }

    // ------------------------------------------------------------------ 预测任务

    private static final String TASK_SELECT = "select t.*, d.dc_name, m.model_name from predict_task t "
            + "left join dc_info d on d.dc_code = t.dc_code "
            + "left join model_registry m on m.model_code = t.model_code ";

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, i) -> new TaskRow(
            rs.getLong("task_id"),
            rs.getString("task_code"),
            rs.getString("task_name"),
            rs.getString("dc_code"),
            rs.getString("dc_name"),
            rs.getString("model_code"),
            rs.getString("model_name"),
            rs.getInt("horizon"),
            rs.getString("status"),
            toLocal(rs, "train_start"),
            toLocal(rs, "train_end"),
            toLocal(rs, "valid_start"),
            toLocal(rs, "exec_time"),
            (Long) rs.getObject("exec_millis", Long.class),
            (Double) rs.getObject("mae", Double.class),
            (Double) rs.getObject("rmse", Double.class),
            (Double) rs.getObject("mape", Double.class),
            (Integer) rs.getObject("sample_count", Integer.class),
            rs.getString("remark"),
            toLocal(rs, "create_time"));

    public long insertTask(TaskRow r)
    {
        jdbc.update(
                "insert into predict_task(task_code, task_name, dc_code, model_code, horizon, status, "
                        + "train_start, train_end, valid_start, exec_time, exec_millis, mae, rmse, mape, "
                        + "sample_count, remark, create_by, create_time, update_time) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                r.taskCode(), r.taskName(), r.dcCode(), r.modelCode(), r.horizon(), r.status(),
                toTs(r.trainStart()), toTs(r.trainEnd()), toTs(r.validStart()), toTs(r.execTime()),
                r.execMillis(), r.mae(), r.rmse(), r.mape(), r.sampleCount(), r.remark(), "admin");
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return id == null ? -1L : id;
    }

    private static Timestamp toTs(LocalDateTime t)
    {
        return t == null ? null : Timestamp.valueOf(t);
    }

    public void updateTaskStatus(long taskId, String status)
    {
        jdbc.update("update predict_task set status = ?, update_time = now() where task_id = ?", status, taskId);
    }

    /** 任务执行完成后回写耗时与精度指标 */
    public void finishTask(long taskId, long costMillis, com.ruoyi.system.dispatch.DispatchModels.Metric metric,
            LocalDateTime validStart)
    {
        jdbc.update(
                "update predict_task set status = 'COMPLETED', exec_time = now(), exec_millis = ?, "
                        + "mae = ?, rmse = ?, mape = ?, sample_count = ?, valid_start = ?, update_time = now() "
                        + "where task_id = ?",
                costMillis, metric.mae(), metric.rmse(), metric.mape(), metric.samples(),
                toTs(validStart), taskId);
    }

    /** 删除某任务的预测结果（重新执行前清理） */
    public void deleteTaskResults(long taskId)
    {
        jdbc.update("delete from predict_result where task_id = ?", taskId);
    }

    public void deleteTask(long taskId)
    {
        jdbc.update("delete from predict_result where task_id = ?", taskId);
        jdbc.update("delete from predict_task where task_id = ?", taskId);
    }

    public List<TaskRow> listTasks(String dcCode, int limit)
    {
        String where = (dcCode == null || dcCode.isBlank()) ? "" : "where t.dc_code = ? ";
        String sql = TASK_SELECT + where + "order by t.task_id desc limit ?";
        return dcCode == null || dcCode.isBlank()
                ? jdbc.query(sql, TASK_MAPPER, limit)
                : jdbc.query(sql, TASK_MAPPER, dcCode, limit);
    }

    public TaskRow findTask(long taskId)
    {
        List<TaskRow> list = jdbc.query(TASK_SELECT + "where t.task_id = ?", TASK_MAPPER, taskId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void batchInsertForecast(long taskId, List<ForecastPoint> points)
    {
        if (points == null || points.isEmpty())
        {
            return;
        }
        jdbc.batchUpdate(
                "insert into predict_result(task_id, seq, ts, actual_kw, predict_kw) values(?,?,?,?,?)",
                points,
                200,
                (ps, p) -> {
                    ps.setLong(1, taskId);
                    ps.setInt(2, p.seq());
                    ps.setTimestamp(3, Timestamp.valueOf(p.ts()));
                    if (p.actualKw() == null)
                    {
                        ps.setNull(4, java.sql.Types.DECIMAL);
                    }
                    else
                    {
                        ps.setDouble(4, p.actualKw());
                    }
                    ps.setDouble(5, p.predictKw());
                });
    }

    public List<ForecastPoint> listForecast(long taskId)
    {
        return jdbc.query(
                "select seq, ts, actual_kw, predict_kw from predict_result where task_id = ? order by seq",
                (rs, i) -> new ForecastPoint(
                        rs.getInt("seq"),
                        rs.getTimestamp("ts").toLocalDateTime(),
                        (Double) rs.getObject("actual_kw", Double.class),
                        rs.getDouble("predict_kw")),
                taskId);
    }

    // ------------------------------------------------------------------ 模型台账

    private static final RowMapper<ModelRow> MODEL_MAPPER = (rs, i) -> new ModelRow(
            rs.getLong("model_id"),
            rs.getString("model_code"),
            rs.getString("model_name"),
            rs.getString("model_type"),
            rs.getString("status"),
            rs.getString("version"),
            rs.getString("dc_code"),
            toLocal(rs, "train_time"),
            (Double) rs.getObject("mae", Double.class),
            (Double) rs.getObject("rmse", Double.class),
            (Double) rs.getObject("mape", Double.class),
            (Integer) rs.getObject("train_samples", Integer.class),
            (Integer) rs.getObject("valid_samples", Integer.class),
            rs.getString("params"),
            rs.getString("description"));

    public List<ModelRow> listModels()
    {
        return jdbc.query("select * from model_registry order by model_id", MODEL_MAPPER);
    }

    public ModelRow findModel(long modelId)
    {
        List<ModelRow> list = jdbc.query("select * from model_registry where model_id = ?", MODEL_MAPPER, modelId);
        return list.isEmpty() ? null : list.get(0);
    }

    public ModelRow findModelByCode(String code)
    {
        List<ModelRow> list = jdbc.query("select * from model_registry where model_code = ?", MODEL_MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateModelMetrics(String modelCode, String dcCode, double mae, double rmse, double mape,
            int trainSamples, int validSamples)
    {
        jdbc.update(
                "update model_registry set status = 'TRAINED', dc_code = ?, train_time = now(), "
                        + "mae = ?, rmse = ?, mape = ?, train_samples = ?, valid_samples = ? where model_code = ?",
                dcCode, mae, rmse, mape, trainSamples, validSamples, modelCode);
    }

    // ------------------------------------------------------------------ 看板聚合

    public java.util.Map<String, Object> planSummary(String dcCode)
    {
        String where = (dcCode == null || dcCode.isBlank()) ? "" : "where dc_code = ? ";
        String sql = "select count(*) plans, coalesce(sum(cost_saving),0) saving, "
                + "coalesce(sum(emission_reduction),0) emission, "
                + "coalesce(avg(cost_saving_rate),0) rate, coalesce(max(plan_id),0) latest from dispatch_plan " + where;
        return dcCode == null || dcCode.isBlank()
                ? jdbc.queryForMap(sql)
                : jdbc.queryForMap(sql, dcCode);
    }

    /** 删除某计划产生的自动潜力分析记录（重复回填前清理） */
    public int deleteAutoAnalysisByPlan(long planId)
    {
        return jdbc.update("delete from potential_analysis where plan_id = ? and source = 'AUTO'", planId);
    }

    /** 调度策略 */

    /** 读取调度策略中与求解相关的阈值，缺省返回 null 由上层回落默认值 */
    public com.ruoyi.system.dispatch.DispatchModels.StrategyParams findStrategy(Long strategyId)
    {
        String sql = "select strategy_id, strategy_code, strategy_name, strategy_type, status, "
                + "storage_soc_target, max_consumption_ratio, load_transfer_threshold from dispatch_strategy ";
        List<com.ruoyi.system.dispatch.DispatchModels.StrategyParams> rows;
        if (strategyId == null)
        {
            rows = jdbc.query(sql + "where status = '0' order by strategy_id limit 1", (rs, i) -> toStrategy(rs));
        }
        else
        {
            rows = jdbc.query(sql + "where strategy_id = ?", (rs, i) -> toStrategy(rs), strategyId);
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 全部调度策略，按编号排序 */
    public List<com.ruoyi.system.dispatch.DispatchModels.StrategyParams> listStrategies()
    {
        return jdbc.query("select strategy_id, strategy_code, strategy_name, strategy_type, status, "
                + "storage_soc_target, max_consumption_ratio, load_transfer_threshold "
                + "from dispatch_strategy order by strategy_id", (rs, i) -> toStrategy(rs));
    }

    /**
     * 把某条策略设为启用，其余全部停用。
     * <p>
     * 求解只认「启用中的第一条」，所以启用必须是单选，否则同一时刻两套权重会互相打架。
     */
    public int activateStrategy(long strategyId)
    {
        jdbc.update("update dispatch_strategy set status = '1', update_time = now() where strategy_id <> ?",
                strategyId);
        return jdbc.update(
                "update dispatch_strategy set status = '0', update_time = now() where strategy_id = ?",
                strategyId);
    }

    /** 修改策略里的储能 SOC 目标（百分比，0–100） */
    public int updateStrategySocTarget(long strategyId, double percent)
    {
        return jdbc.update(
                "update dispatch_strategy set storage_soc_target = ?, update_time = now() where strategy_id = ?",
                java.math.BigDecimal.valueOf(percent), strategyId);
    }

    private static com.ruoyi.system.dispatch.DispatchModels.StrategyParams toStrategy(ResultSet rs) throws SQLException
    {
        java.math.BigDecimal soc = rs.getBigDecimal("storage_soc_target");
        java.math.BigDecimal ratio = rs.getBigDecimal("max_consumption_ratio");
        java.math.BigDecimal thr = rs.getBigDecimal("load_transfer_threshold");
        String status = rs.getString("status");
        return new com.ruoyi.system.dispatch.DispatchModels.StrategyParams(
                rs.getLong("strategy_id"),
                rs.getString("strategy_name"),
                rs.getString("strategy_code"),
                rs.getString("strategy_type"),
                soc == null ? 0.55 : soc.doubleValue() / 100.0,
                ratio == null ? 0.92 : ratio.doubleValue() / 100.0,
                thr == null ? 400 : thr.doubleValue(),
                "0".equals(status) ? 0 : 1);
    }

    // ------------------------------------------------------------------ 看板：装机与设备

    /** 微网装机汇总 */
    public java.util.Map<String, Object> fleetCapacity()
    {
        return jdbc.queryForMap("select count(*) dc_count, coalesce(sum(it_capacity_kw),0) it_kw, "
                + "coalesce(sum(pv_capacity_kw),0) pv_kw, coalesce(sum(storage_capacity_kwh),0) storage_kwh, "
                + "coalesce(sum(storage_power_kw),0) storage_kw, coalesce(sum(gas_capacity_kw),0) gas_kw "
                + "from dc_info where status = '0'");
    }

    /** 按数据中心汇总最新一天的优化效果 */
    public List<java.util.Map<String, Object>> dcPlanBreakdown()
    {
        return jdbc.queryForList(
                "select p.dc_code as dcCode, d.dc_name as dcName, max(p.plan_id) as planId, "
                        + "max(p.opt_cost) as optCost, max(p.cost_saving_rate) as rate "
                        + "from dispatch_plan p left join dc_info d on d.dc_code = p.dc_code "
                        + "group by p.dc_code, d.dc_name order by p.dc_code");
    }

    /** 历史数据时间范围 */
    public java.util.Map<String, Object> historyRange()
    {
        return jdbc.queryForMap("select count(*) cnt, min(ts) min_ts, max(ts) max_ts from dc_load_history");
    }

    /** 最新一个已完成的预测任务 */
    public TaskRow latestCompletedTask()
    {
        List<TaskRow> list = jdbc.query(TASK_SELECT + "where t.status = 'COMPLETED' order by t.task_id desc limit 1",
                TASK_MAPPER);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 每个数据中心最近一次已完成的预测任务 */
    public List<TaskRow> latestCompletedTaskPerDc()
    {
        return jdbc.query(TASK_SELECT
                + "where t.status = 'COMPLETED' and t.task_id = ("
                + "  select max(t2.task_id) from predict_task t2 "
                + "  where t2.dc_code = t.dc_code and t2.status = 'COMPLETED') "
                + "order by t.dc_code", TASK_MAPPER);
    }
}
