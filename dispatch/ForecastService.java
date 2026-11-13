package com.ruoyi.system.dispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.ForecastPoint;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;
import com.ruoyi.system.dispatch.DispatchModels.Metric;
import com.ruoyi.system.dispatch.DispatchModels.ModelRow;
import com.ruoyi.system.dispatch.DispatchModels.TaskRow;

/**
 * 负荷预测服务。
 * <p>
 * 以历史负荷序列为基础，用留出法（最后一天作验证集）评估模型精度，
 * 并把任务、结果与指标全部落库，替代此前的内存模拟实现。
 *
 * @author ruoyi
 */
@Service
public class ForecastService
{
    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    /** 训练所需最少天数 */
    public static final int MIN_DAYS = 7;

    /** 任务编号后缀计数器，避免同一秒内多次执行产生重复编号 */
    private static final java.util.concurrent.atomic.AtomicInteger TASK_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private final DispatchDao dao;
    private final TelemetrySimulator simulator;

    public ForecastService(DispatchDao dao, TelemetrySimulator simulator)
    {
        this.dao = dao;
        this.simulator = simulator;
    }

    // ------------------------------------------------------------------ 评估核心

    /**
     * 用留出法评估模型：最后一天（96 点）作验证集。
     *
     * @param y    完整负荷序列
     * @param code 模型编码
     * @return 预测值与指标
     */
    public static Evaluation evaluate(double[] y, String code)
    {
        int horizon = TelemetrySimulator.SLOTS_PER_DAY;
        if (y.length < horizon * 3)
        {
            throw new IllegalStateException("历史数据不足，至少需要 3 天数据，当前仅 "
                    + (y.length / horizon) + " 天");
        }
        int trainLen = y.length - horizon;
        double[] train = new double[trainLen];
        System.arraycopy(y, 0, train, 0, trainLen);

        ForecastModels.Model model = ForecastModels.create(code);
        model.fit(train);
        double[] pred = model.forecast(horizon);

        double[] actual = new double[horizon];
        System.arraycopy(y, trainLen, actual, 0, horizon);

        double sae = 0;
        double sse = 0;
        double sape = 0;
        int mapeCnt = 0;
        for (int i = 0; i < horizon; i++)
        {
            double e = pred[i] - actual[i];
            sae += Math.abs(e);
            sse += e * e;
            if (Math.abs(actual[i]) > 1e-6)
            {
                sape += Math.abs(e) / Math.abs(actual[i]);
                mapeCnt++;
            }
        }
        double mae = sae / horizon;
        double rmse = Math.sqrt(sse / horizon);
        double mape = mapeCnt == 0 ? 0 : sape / mapeCnt * 100.0;

        Metric metric = new Metric(round(mae, 4), round(rmse, 4), round(mape, 4), horizon);
        return new Evaluation(pred, actual, metric, trainLen);
    }

    /** 评估结果载体 */
    public record Evaluation(double[] predict, double[] actual, Metric metric, int trainLen)
    {
    }

    // ------------------------------------------------------------------ 任务执行

    /**
     * 执行一次预测任务：评估、落库结果、更新任务指标。
     */
    public TaskRow executeTask(long taskId)
    {
        TaskRow task = dao.findTask(taskId);
        if (task == null)
        {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        simulator.ensureHistory(MIN_DAYS + 1);

        long begin = System.currentTimeMillis();
        List<LoadPoint> points = dao.history(task.dcCode(), LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0));
        double[] y = new double[points.size()];
        for (int i = 0; i < y.length; i++)
        {
            y[i] = points.get(i).loadKw();
        }
        Evaluation ev = evaluate(y, task.modelCode());
        long cost = System.currentTimeMillis() - begin;

        int horizon = ev.metric().samples();
        int validStartIdx = y.length - horizon;
        LocalDateTime validStart = points.get(validStartIdx).ts();

        List<ForecastPoint> results = new ArrayList<>(horizon);
        for (int i = 0; i < horizon; i++)
        {
            results.add(new ForecastPoint(i + 1, validStart.plusMinutes(15L * i),
                    round(ev.actual()[i], 2), round(ev.predict()[i], 2)));
        }

        dao.deleteTaskResults(taskId);
        dao.batchInsertForecast(taskId, results);
        dao.finishTask(taskId, cost, ev.metric(), validStart);

        log.info("预测任务 {} 完成：模型 {} 数据中心 {} MAPE={}%",
                task.taskCode(), task.modelCode(), task.dcCode(), ev.metric().mape());
        return dao.findTask(taskId);
    }

    /**
     * 新建并立即执行一个预测任务。
     */
    public TaskRow runTask(String dcCode, String modelCode, String taskName)
    {
        DcInfo dc = dao.findDc(dcCode);
        if (dc == null)
        {
            throw new IllegalArgumentException("数据中心不存在: " + dcCode);
        }
        simulator.ensureHistory(MIN_DAYS + 1);

        LocalDate maxDate = dao.maxHistoryDate();
        if (maxDate == null)
        {
            throw new IllegalStateException("尚无历史负荷数据，无法预测");
        }
        String code = "PT" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(LocalDateTime.now()) + String.format("%03d", TASK_SEQ.incrementAndGet() % 1000);
        TaskRow draft = new TaskRow(0, code, taskName, dcCode, dc.dcName(),
                modelCode, null, TelemetrySimulator.SLOTS_PER_DAY, "PENDING",
                maxDate.minusDays(MIN_DAYS).atStartOfDay(), maxDate.minusDays(1).atStartOfDay(),
                maxDate.atStartOfDay(), null, null, null, null, null, null, null, null);
        long id = dao.insertTask(draft);
        return executeTask(id);
    }

    /**
     * 训练模型：在全部数据中心上评估，写入平均指标。
     */
    public Map<String, Object> trainModel(String modelCode)
    {
        simulator.ensureHistory(MIN_DAYS + 1);
        ModelRow model = dao.findModelByCode(modelCode);
        if (model == null)
        {
            throw new IllegalArgumentException("模型不存在: " + modelCode);
        }
        List<DcInfo> dcs = dao.listDc();
        List<Map<String, Object>> detail = new ArrayList<>();
        double sae = 0;
        double sse = 0;
        double sape = 0;
        int cnt = 0;
        int totalTrain = 0;
        int totalValid = 0;

        for (DcInfo dc : dcs)
        {
            double[] y = loadSeries(dc.dcCode());
            if (y.length < TelemetrySimulator.SLOTS_PER_DAY * 3)
            {
                continue;
            }
            Evaluation ev = evaluate(y, modelCode);
            Metric m = ev.metric();
            sae += m.mae() * m.samples();
            sse += m.rmse() * m.rmse() * m.samples();
            sape += m.mape() * m.samples();
            cnt += m.samples();
            totalTrain += ev.trainLen();
            totalValid += m.samples();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dcCode", dc.dcCode());
            row.put("dcName", dc.dcName());
            row.put("mae", m.mae());
            row.put("rmse", m.rmse());
            row.put("mape", m.mape());
            detail.add(row);
        }
        if (cnt == 0)
        {
            throw new IllegalStateException("没有可用的历史数据");
        }
        double mae = round(sae / cnt, 4);
        double rmse = round(Math.sqrt(sse / cnt), 4);
        double mape = round(sape / cnt, 4);
        dao.updateModelMetrics(modelCode, "ALL", mae, rmse, mape, totalTrain, totalValid);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelCode", modelCode);
        result.put("modelName", model.modelName());
        result.put("mae", mae);
        result.put("rmse", rmse);
        result.put("mape", mape);
        result.put("detail", detail);
        log.info("模型 {} 训练完成：MAE={} RMSE={} MAPE={}%", modelCode, mae, rmse, mape);
        return result;
    }

    /**
     * 对某数据中心跑全部四个模型，返回按 MAPE 升序的结果。
     */
    public List<Map<String, Object>> compareModels(String dcCode)
    {
        simulator.ensureHistory(MIN_DAYS + 1);
        double[] y = loadSeries(dcCode);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : ForecastModels.codes())
        {
            Metric m = evaluate(y, code).metric();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelCode", code);
            ModelRow mr = dao.findModelByCode(code);
            row.put("modelName", mr == null ? code : mr.modelName());
            row.put("mae", m.mae());
            row.put("rmse", m.rmse());
            row.put("mape", m.mape());
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare((double) a.get("mape"), (double) b.get("mape")));
        return rows;
    }

    /** 取某数据中心的完整负荷序列 */
    public double[] loadSeries(String dcCode)
    {
        List<LoadPoint> points = dao.history(dcCode, LocalDateTime.of(2000, 1, 1, 0, 0),
                LocalDateTime.of(2100, 1, 1, 0, 0));
        double[] y = new double[points.size()];
        for (int i = 0; i < y.length; i++)
        {
            y[i] = points.get(i).loadKw();
        }
        return y;
    }

    /** 任务列表查询 */
    public List<TaskRow> listTasks(String dcCode, int limit)
    {
        return dao.listTasks(dcCode, limit <= 0 ? 200 : limit);
    }

    private static double round(double v, int scale)
    {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
