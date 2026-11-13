package com.ruoyi.system.dispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.HistorySlot;

/**
 * 数据中心负荷与新能源出力模拟器。
 * <p>
 * 系统无法接入真实 SCADA 数据，因此以公开的负荷规律构造一段可复现的历史序列：
 * 日周期（下午高峰、凌晨低谷）、周周期（周末下降）、气象驱动的制冷负荷、
 * 光伏出力随太阳高度角变化、分时电价，以及偶发的批处理作业冲击。
 * <p>
 * 所有随机项由 {@code (数据中心, 日期, 时段)} 的哈希确定性生成，
 * 同一参数下重复生成结果完全一致，便于实验复现。
 *
 * @author ruoyi
 */
@Service
public class TelemetrySimulator
{
    private static final Logger log = LoggerFactory.getLogger(TelemetrySimulator.class);

    /** 每日采样点数（15 分钟粒度） */
    public static final int SLOTS_PER_DAY = 96;
    /** 单时段时长（小时） */
    public static final double STEP_HOURS = 0.25;

    private final DispatchDao dao;

    public TelemetrySimulator(DispatchDao dao)
    {
        this.dao = dao;
    }

    /**
     * 确保历史数据存在。
     * <p>
     * 仅在历史表完全为空时生成模拟数据。只要库中已有任何记录（例如通过 CSV 导入的真实现场数据），
     * 就一律不再写入，避免模拟数据覆盖真实数据。
     *
     * @param days 需要覆盖的天数
     * @return 本次写入的采样点数，未生成时返回 0
     */
    public synchronized int ensureHistory(int days)
    {
        List<DcInfo> dcs = dao.listDc();
        if (dcs.isEmpty())
        {
            log.warn("未配置数据中心档案，跳过历史数据生成");
            return 0;
        }
        long existing = dao.historyCount();
        if (existing > 0)
        {
            log.debug("历史表已有 {} 条记录，跳过模拟数据生成", existing);
            return 0;
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1L);
        boolean fresh = true;
        List<HistorySlot> batch = new ArrayList<>(4096);
        int written = 0;
        for (DcInfo dc : dcs)
        {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1))
            {
                batch.addAll(generateDay(dc, d));
                if (batch.size() >= 8192)
                {
                    dao.batchInsertHistory(batch, !fresh);
                    written += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty())
        {
            dao.batchInsertHistory(batch, !fresh);
            written += batch.size();
        }
        log.info("历史数据生成完成，共 {} 个采样点，覆盖 {} 至 {}", written, start, end);
        return written;
    }

    /**
     * 把历史数据补到今天。
     * <p>
     * 演示库常见的问题：数据是几天前生成的，之后系统时钟往前走，看板就变成了「历史」。
     * 这里按每个数据中心各自的最新一天往后补，只写它自己缺的日期；
     * 表为空时等价于 {@link #ensureHistory(int)}。
     *
     * @param keepDays 表为空时生成的天数
     * @return 本次写入的采样点数
     */
    public synchronized int topUp(int keepDays)
    {
        List<DcInfo> dcs = dao.listDc();
        if (dcs.isEmpty())
        {
            log.warn("未配置数据中心档案，跳过历史数据生成");
            return 0;
        }
        if (dao.maxHistoryDate() == null)
        {
            return ensureHistory(keepDays);
        }
        LocalDate today = LocalDate.now();
        int written = 0;
        for (DcInfo dc : dcs)
        {
            LocalDate last = dao.maxHistoryDateOf(dc.dcCode());
            LocalDate from = last == null ? today.minusDays(keepDays - 1L) : last.plusDays(1);
            if (from.isAfter(today))
            {
                continue;
            }
            List<HistorySlot> batch = new ArrayList<>(4096);
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1))
            {
                batch.addAll(generateDay(dc, d));
                if (batch.size() >= 8192)
                {
                    dao.batchInsertHistory(batch, true);
                    written += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty())
            {
                dao.batchInsertHistory(batch, true);
                written += batch.size();
            }
        }
        if (written > 0)
        {
            log.info("历史数据补齐到 {}，本次写入 {} 个采样点", today, written);
        }
        return written;
    }

    /**
     * 生成某数据中心某一天的 96 个采样点。
     */
    public List<HistorySlot> generateDay(DcInfo dc, LocalDate date)
    {
        List<HistorySlot> list = new ArrayList<>(SLOTS_PER_DAY);
        boolean workday = date.getDayOfWeek().getValue() <= 5;
        double dayPhase = hash01(dc.dcCode(), date.toEpochDay()) * Math.PI * 2;

        for (int slot = 0; slot < SLOTS_PER_DAY; slot++)
        {
            LocalDateTime ts = date.atStartOfDay().plusMinutes(15L * slot);
            double hour = slot * STEP_HOURS;

            // ---- 气温：日均 + 日较差 + 天气扰动
            double temp = 22.0 + 8.0 * Math.sin(2 * Math.PI * (hour - 14.0) / 24.0)
                    + 2.5 * Math.sin(dayPhase + date.getDayOfYear() * 0.17);

            // ---- IT 负荷：日周期 + 周末系数 + 随机扰动
            double daily = 1.0 + 0.17 * Math.sin(2 * Math.PI * (hour - 9.0) / 24.0)
                    + 0.05 * Math.sin(4 * Math.PI * (hour - 9.0) / 24.0);
            double weekend = workday ? 1.0 : 0.86;
            double noise = 1.0 + 0.035 * (hash01(dc.dcCode() + "/n", date.toEpochDay() * 96 + slot) - 0.5) * 2;
            double it = dc.baseLoadKw() * daily * weekend * noise;

            // ---- 偶发批处理作业：约 1.5% 的时段触发，持续 4 个时段
            double job = 0;
            for (int back = 0; back < 4; back++)
            {
                int s0 = slot - back;
                if (s0 >= 0 && hash01(dc.dcCode() + "/job", date.toEpochDay() * 96 + s0) < 0.015)
                {
                    job += dc.itCapacityKw() * 0.05 * (1.0 - back * 0.2);
                }
            }
            it += job;

            // ---- 制冷负荷：与气温线性正相关
            double cooling = it * 0.30 * (1 + 0.025 * (temp - 24.0));

            double load = Math.max(dc.baseLoadKw() * 0.45, it + cooling);

            // ---- 光伏：太阳高度角 + 云量扰动
            double sun = 0;
            if (hour > 6.0 && hour < 18.0)
            {
                sun = Math.sin(Math.PI * (hour - 6.0) / 12.0);
            }
            double cloud = 0.72 + 0.28 * (0.5 + 0.5 * Math.sin(dayPhase + hour * 0.7));
            double pv = dc.pvCapacityKw() * sun * cloud;
            if (hash01(dc.dcCode() + "/rain", date.toEpochDay()) < 0.15)
            {
                pv *= 0.45;
            }

            list.add(new HistorySlot(dc.dcCode(), ts, round(load, 2), round(pv, 2),
                    priceAt(ts), round(temp, 2), workday));
        }
        return list;
    }

    /**
     * 分时电价（元/kWh）：谷段 0-8，峰段 10-12 与 17-21，其余平段。
     */
    public static double priceAt(LocalDateTime ts)
    {
        int h = ts.getHour();
        if (h < 8)
        {
            return 0.35;
        }
        if ((h >= 10 && h < 12) || (h >= 17 && h < 21))
        {
            return 1.05;
        }
        return 0.68;
    }

    /** 电网排放因子 kgCO2/kWh */
    public static final double EMISSION_FACTOR = 0.581;

    /** 燃气轮机发电燃料成本 元/kWh */
    public static final double GAS_COST = 0.78;

    /** 燃气轮机排放因子 kgCO2/kWh */
    public static final double GAS_EMISSION_FACTOR = 0.42;

    private static double round(double v, int scale)
    {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    private static double hash01(String key, long salt)
    {
        long x = key.hashCode() * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return (x >>> 11) * 0x1.0p-53;
    }
}
