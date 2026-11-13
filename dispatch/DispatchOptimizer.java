package com.ruoyi.system.dispatch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;
import com.ruoyi.system.dispatch.DispatchModels.OptimizeOutcome;
import com.ruoyi.system.dispatch.DispatchModels.PlanItem;

/**
 * 数据中心微网调度优化器。
 * <p>
 * 建立 24 小时、96 个 15 分钟时段的混合整数线性规划模型，同时决策购电量、
 * 新能源消纳量、储能充放电、燃气轮机出力与可延迟算力作业的时段分配，
 * 目标为购电成本、碳排放成本与储能损耗之和最小。
 * <p>
 * 可延迟作业的建模方式：把负荷拆为刚性部分与可延迟部分，可延迟能量在
 * 一个调度块（默认 4 小时）内自由重排但总量守恒。这正是数据中心相对
 * 传统工业负荷的独特柔性所在，也是"算电协同"的落点。
 *
 * @author ruoyi
 */
@Service
public class DispatchOptimizer
{
    private static final Logger log = LoggerFactory.getLogger(DispatchOptimizer.class);

    /** 本地库加载状态 */
    private static volatile boolean nativeReady = false;
    private static volatile String nativeError = null;

    static
    {
        try
        {
            Loader.loadNativeLibraries();
            nativeReady = true;
        }
        catch (Throwable e)
        {
            nativeError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("OR-Tools 本地库加载失败，调度求解将不可用：{}", nativeError);
        }
    }

    /**
     * 求解参数。
     * <p>
     * 前七个是物理与价格参数；后四个是策略权重——不同调度策略改的就是这组权重，
     * 目标函数本身只有一条，变的是各项的分量。
     *
     * @param costWeight      电费权重（购电 + 燃气）
     * @param emissionWeight  碳排权重（乘 emissionPrice）
     * @param consumptionBonus 每消纳 1 kWh 光伏的奖励（元/kWh），鼓励自用
     * @param demandWeight    最大需量电费权重（对应削峰）
     */
    public record Params(
            double socTarget,
            double maxConsumptionRatio,
            double shiftCapKw,
            double emissionPrice,
            double degradeCost,
            double demandCharge,
            int blockPeriods,
            double costWeight,
            double emissionWeight,
            double consumptionBonus,
            double demandWeight,
            double gridPenalty)
    {
        public static Params defaults()
        {
            return new Params(0.55, 0.92, 400, 0.05, 0.02, 1.2, 16, 1, 1, 0, 1, 0);
        }

        /** 按调度策略类型给权重：成本优先 / 消纳优先 / 削峰优先 */
        public static Params ofStrategy(String strategyType, double socTarget, double maxConsumptionRatio,
                double shiftCapKw)
        {
            return switch (strategyType == null ? "" : strategyType)
            {
                // 微网：少依赖大电网，宁可多烧气、多循环储能，也要把购电量压下来
                case "MICROGRID_DISPATCH" -> new Params(socTarget, maxConsumptionRatio, shiftCapKw,
                        0.05, 0.02, 1.2, 16, 1.0, 2.0, 0.30, 0.8, 0.15);
                case "LOAD_DISPATCH" -> new Params(socTarget, maxConsumptionRatio, shiftCapKw,
                        0.05, 0.02, 1.2, 16, 1.0, 1.0, 0.0, 3.0, 0);
                default -> new Params(socTarget, maxConsumptionRatio, shiftCapKw,
                        0.05, 0.02, 1.2, 16, 1.0, 1.0, 0.0, 1.0, 0);
            };
        }
    }

    public boolean isNativeReady()
    {
        return nativeReady;
    }

    public String nativeError()
    {
        return nativeError;
    }

    /**
     * 求解单日调度计划。
     *
     * @param dc   数据中心参数
     * @param day  当日 96 个负荷点（含电价与新能源可用出力）
     * @param p    求解参数
     */
    public OptimizeOutcome solve(DcInfo dc, List<LoadPoint> day, Params p)
    {
        long begin = System.currentTimeMillis();
        int T = day.size();
        double dt = TelemetrySimulator.STEP_HOURS;
        double ef = TelemetrySimulator.EMISSION_FACTOR;

        if (!nativeReady)
        {
            return new OptimizeOutcome("UNAVAILABLE", "求解器不可用：" + nativeError, "-", 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        // ---------- 负荷拆分：刚性 + 可延迟
        double[] rigid = new double[T];
        double[] pvAvail = new double[T];
        double[] price = new double[T];
        double[] shiftEnergy = new double[T];
        double totalPv = 0;
        for (int t = 0; t < T; t++)
        {
            LoadPoint lp = day.get(t);
            rigid[t] = lp.loadKw() * (1 - dc.deferrableRatio());
            shiftEnergy[t] = lp.loadKw() * dc.deferrableRatio();
            pvAvail[t] = lp.pvAvailKw();
            price[t] = lp.price();
            totalPv += lp.pvAvailKw();
        }

        // 每个调度块的可延迟能量需求（kWh）
        int blocks = (T + p.blockPeriods() - 1) / p.blockPeriods();
        double[] blockDemand = new double[blocks];
        for (int t = 0; t < T; t++)
        {
            blockDemand[t / p.blockPeriods()] += shiftEnergy[t] * dt;
        }

        // ---------- 上线
        MPSolver solver = MPSolver.createSolver("CBC");
        if (solver == null)
        {
            solver = MPSolver.createSolver("SCIP");
        }
        if (solver == null)
        {
            return new OptimizeOutcome("UNAVAILABLE", "未找到可用的 MILP 求解器", "-", 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
        solver.setTimeLimit(20000);
        String solverName = solver.solverVersion();

        MPVariable[] grid = new MPVariable[T];
        MPVariable[] pvUse = new MPVariable[T];
        MPVariable[] ch = new MPVariable[T];
        MPVariable[] dis = new MPVariable[T];
        MPVariable[] gas = new MPVariable[T];
        MPVariable[] sh = new MPVariable[T];
        MPVariable[] soc = new MPVariable[T];
        MPVariable[] z = new MPVariable[T];

        double socLo = Math.min(dc.socMin(), dc.socMax() - 0.01);
        double socHi = Math.max(dc.socMax(), socLo + 0.01);
        double socTarget = Math.max(socLo, Math.min(socHi, p.socTarget()));

        for (int t = 0; t < T; t++)
        {
            grid[t] = solver.makeNumVar(0, dc.gridCapacityKw(), "grid_" + t);
            pvUse[t] = solver.makeNumVar(0, pvAvail[t], "pv_" + t);
            ch[t] = solver.makeNumVar(0, dc.storagePowerKw(), "ch_" + t);
            dis[t] = solver.makeNumVar(0, dc.storagePowerKw(), "dis_" + t);
            gas[t] = solver.makeNumVar(0, dc.gasCapacityKw(), "gas_" + t);
            sh[t] = solver.makeNumVar(0, Math.max(1, p.shiftCapKw()), "sh_" + t);
            soc[t] = solver.makeNumVar(socLo, socHi, "soc_" + t);
            z[t] = solver.makeBoolVar("z_" + t);
        }

        // ---------- 功率平衡：购电 + 新能源 + 燃气 + 放电 = 刚性 + 可延迟 + 充电
        for (int t = 0; t < T; t++)
        {
            MPConstraint bal = solver.makeConstraint(0, 0, "bal_" + t);
            bal.setCoefficient(grid[t], 1);
            bal.setCoefficient(pvUse[t], 1);
            bal.setCoefficient(gas[t], 1);
            bal.setCoefficient(dis[t], 1);
            bal.setCoefficient(ch[t], -1);
            bal.setCoefficient(sh[t], -1);
            bal.setBounds(rigid[t], rigid[t]);
        }

        // ---------- 可延迟能量守恒：每个调度块内总量不变
        for (int b = 0; b < blocks; b++)
        {
            MPConstraint c = solver.makeConstraint(blockDemand[b], blockDemand[b], "blk_" + b);
            for (int t = b * p.blockPeriods(); t < Math.min(T, (b + 1) * p.blockPeriods()); t++)
            {
                c.setCoefficient(sh[t], dt);
            }
        }

        // ---------- 储能荷电状态动态
        double capacity = Math.max(1, dc.storageCapacityKwh());
        for (int t = 0; t < T; t++)
        {
            MPConstraint c = solver.makeConstraint(0, 0, "soc_" + t);
            c.setCoefficient(soc[t], 1);
            if (t == 0)
            {
                c.setCoefficient(ch[t], -dc.effCharge() * dt / capacity);
                c.setCoefficient(dis[t], dt / (dc.effDischarge() * capacity));
                c.setBounds(dc.socInit(), dc.socInit());
            }
            else
            {
                c.setCoefficient(soc[t - 1], -1);
                c.setCoefficient(ch[t], -dc.effCharge() * dt / capacity);
                c.setCoefficient(dis[t], dt / (dc.effDischarge() * capacity));
            }
        }

        // ---------- 充放电互斥
        for (int t = 0; t < T; t++)
        {
            MPConstraint c1 = solver.makeConstraint(-MPSolver.infinity(), 0, "chz_" + t);
            c1.setCoefficient(ch[t], 1);
            c1.setCoefficient(z[t], -dc.storagePowerKw());

            MPConstraint c2 = solver.makeConstraint(-MPSolver.infinity(), dc.storagePowerKw(), "disz_" + t);
            c2.setCoefficient(dis[t], 1);
            c2.setCoefficient(z[t], dc.storagePowerKw());
        }

        // ---------- 燃气轮机爬坡
        double rampStep = dc.gasRampKwPerMin() * (dt * 60.0);
        for (int t = 1; t < T; t++)
        {
            MPConstraint c = solver.makeConstraint(-rampStep, rampStep, "ramp_" + t);
            c.setCoefficient(gas[t], 1);
            c.setCoefficient(gas[t - 1], -1);
        }

        // ---------- 日末荷电状态不低于目标
        MPConstraint tail = solver.makeConstraint(socTarget, MPSolver.infinity(), "soc_tail");
        tail.setCoefficient(soc[T - 1], 1);

        // ---------- 新能源消纳率下限（软约束：不可行时关闭）
        boolean ratioApplied = false;
        try
        {
            MPConstraint ratio = solver.makeConstraint(p.maxConsumptionRatio() * totalPv, MPSolver.infinity(), "ratio");
            for (int t = 0; t < T; t++)
            {
                ratio.setCoefficient(pvUse[t], 1);
            }
            ratioApplied = true;
        }
        catch (RuntimeException e)
        {
            log.warn("消纳率约束未生效: {}", e.getMessage());
        }

        // ---------- 目标函数：购电成本 + 碳排成本 + 燃气燃料成本 + 储能损耗 + 最大需量电费
        MPVariable peakVar = solver.makeNumVar(0, dc.gridCapacityKw(), "peak");
        for (int t = 0; t < T; t++)
        {
            MPConstraint pc = solver.makeConstraint(0, MPSolver.infinity(), "peak_" + t);
            pc.setCoefficient(peakVar, 1);
            pc.setCoefficient(grid[t], -1);
        }

        MPObjective obj = solver.objective();
        double gasPrice = p.costWeight() * TelemetrySimulator.GAS_COST
                + p.emissionWeight() * p.emissionPrice() * TelemetrySimulator.GAS_EMISSION_FACTOR;
        for (int t = 0; t < T; t++)
        {
            obj.setCoefficient(grid[t],
                    (p.costWeight() * price[t] + p.gridPenalty()
                            + p.emissionWeight() * p.emissionPrice() * ef) * dt);
            obj.setCoefficient(gas[t], gasPrice * dt);
            obj.setCoefficient(ch[t], p.degradeCost() * dt);
            obj.setCoefficient(dis[t], p.degradeCost() * dt);
            // 负系数＝奖励：消纳一度光伏就等于少买一度电之外的额外收益
            if (p.consumptionBonus() > 0)
            {
                obj.setCoefficient(pvUse[t], -p.consumptionBonus() * dt);
            }
        }
        obj.setCoefficient(peakVar, p.demandWeight() * p.demandCharge());
        obj.setMinimization();

        // ---------- 求解
        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE)
        {
            solver.delete();
            return new OptimizeOutcome("INFEASIBLE",
                    "模型无可行解（状态 " + status + (ratioApplied ? "，已施加消纳率约束" : "") + "）",
                    solverName, System.currentTimeMillis() - begin, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        // ---------- 取解
        List<PlanItem> items = new ArrayList<>(T);
        double optCost = 0;
        double optEmission = 0;
        double optPvUsed = 0;
        double optPeak = 0;
        for (int t = 0; t < T; t++)
        {
            double g = grid[t].solutionValue();
            double pu = pvUse[t].solutionValue();
            double c = ch[t].solutionValue();
            double d = dis[t].solutionValue();
            double gs = gas[t].solutionValue();
            double s = sh[t].solutionValue();
            double socVal = soc[t].solutionValue();
            double cost = price[t] * g * dt + TelemetrySimulator.GAS_COST * gs * dt;
            double em = ef * g * dt + TelemetrySimulator.GAS_EMISSION_FACTOR * gs * dt;
            optCost += cost;
            optEmission += em;
            optPvUsed += pu;
            optPeak = Math.max(optPeak, g);

            items.add(new PlanItem(
                    t + 1,
                    day.get(t).ts(),
                    rigid[t] + s,
                    rigid[t],
                    s,
                    pvAvail[t],
                    pu,
                    g,
                    c,
                    d,
                    gs,
                    socVal,
                    price[t],
                    round(em, 3),
                    round(cost, 2)));
        }
        double objective = obj.value();
        long costMillis = System.currentTimeMillis() - begin;
        solver.delete();

        // 需量电费计入综合购电成本，与基线口径保持一致
        optCost += p.demandCharge() * optPeak;

        // ---------- 基线：不调度（储能闲置、按原负荷用电、新能源优先）
        double[] base = baseline(dc, day, dt, ef, p.demandCharge());

        log.info("调度求解完成 dc={} status={} objective={} 耗时={}ms", dc.dcCode(), status, objective, costMillis);
        return new OptimizeOutcome(
                status == MPSolver.ResultStatus.OPTIMAL ? "OPTIMAL" : "FEASIBLE",
                ratioApplied ? "含新能源消纳率约束" : "消纳率约束已放宽",
                solverName,
                costMillis,
                objective,
                round(base[0], 2),
                round(optCost, 2),
                round(base[1], 2),
                round(optEmission, 2),
                round(base[0] > 0 ? base[2] / Math.max(1e-9, base[3]) : 0, 4),
                round(totalPv > 0 ? optPvUsed / totalPv : 0, 4),
                round(base[4], 2),
                round(optPeak, 2),
                items);
    }

    /**
     * 无序调度基线：新能源优先消纳，储能不动作，其余由电网与燃气轮机承担。
     *
     * @return [成本, 碳排放, 消纳量, 可用量, 购电峰值]
     */
    public double[] baseline(DcInfo dc, List<LoadPoint> day, double dt, double ef, double demandCharge)
    {
        double cost = 0;
        double emission = 0;
        double pvUsedTotal = 0;
        double pvAvailTotal = 0;
        double peak = 0;
        for (LoadPoint lp : day)
        {
            double pvUsed = Math.min(lp.pvAvailKw(), lp.loadKw());
            double net = lp.loadKw() - pvUsed;
            double fromGrid = Math.min(Math.max(net, 0), dc.gridCapacityKw());
            double fromGas = Math.max(0, net - dc.gridCapacityKw());
            cost += lp.price() * fromGrid * dt + TelemetrySimulator.GAS_COST * fromGas * dt;
            emission += ef * fromGrid * dt + TelemetrySimulator.GAS_EMISSION_FACTOR * fromGas * dt;
            pvUsedTotal += pvUsed;
            pvAvailTotal += lp.pvAvailKw();
            peak = Math.max(peak, fromGrid);
        }
        cost += demandCharge * peak;
        return new double[] { cost, emission, pvUsedTotal, pvAvailTotal, peak };
    }

    private static double round(double v, int scale)
    {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    /** 供服务层复用的时间点构造 */
    public static LocalDateTime slotTime(LocalDateTime dayStart, int slot)
    {
        return dayStart.plusMinutes(15L * slot);
    }
}
