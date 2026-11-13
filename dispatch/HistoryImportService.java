package com.ruoyi.system.dispatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ruoyi.system.dispatch.DispatchModels.HistorySlot;

/**
 * 历史遥测数据导入。
 * <p>
 * 系统默认使用内置模拟器生成数据，本服务提供一条真实数据的入口：
 * 把现场采集系统导出的负荷与新能源出力序列（CSV）写入 dc_load_history，
 * 调度与预测算法无需任何改动即可基于真实数据运行。
 * <p>
 * CSV 格式（首行可为表头）：
 * <pre>
 * dc_code,ts,load_kw,pv_avail_kw[,price][,outdoor_temp]
 * DC-A,2026-10-01 00:00,712.5,0,0.35,21.3
 * </pre>
 * 缺省电价按分时电价规则推导，缺省温度记为 0。
 *
 * @author ruoyi
 */
@Service
public class HistoryImportService
{
    private static final Logger log = LoggerFactory.getLogger(HistoryImportService.class);

    private static final DateTimeFormatter[] TS_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    private final DispatchDao dao;

    public HistoryImportService(DispatchDao dao)
    {
        this.dao = dao;
    }

    /** 导入结果 */
    public record ImportResult(int accepted, int rejected, int days, String firstTs, String lastTs,
            List<String> errors)
    {
    }

    public ImportResult importCsv(BufferedReader reader) throws IOException
    {
        List<HistorySlot> batch = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        LocalDateTime min = null;
        LocalDateTime max = null;
        List<String> errors = new ArrayList<>();

        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null)
        {
            lineNo++;
            if (line.trim().isEmpty())
            {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 4)
            {
                if (lineNo == 1)
                {
                    continue;
                }
                rejected++;
                if (errors.size() < 5)
                {
                    errors.add("第 " + lineNo + " 行字段不足");
                }
                continue;
            }
            try
            {
                String dcCode = parts[0].trim();
                if (dcCode.isEmpty() || "dc_code".equalsIgnoreCase(dcCode))
                {
                    continue;
                }
                LocalDateTime ts = parseTime(parts[1].trim());
                double load = Double.parseDouble(parts[2].trim());
                double pv = Double.parseDouble(parts[3].trim());
                double price = parts.length > 4 && !parts[4].trim().isEmpty()
                        ? Double.parseDouble(parts[4].trim())
                        : TelemetrySimulator.priceAt(ts);
                double temp = parts.length > 5 && !parts[5].trim().isEmpty()
                        ? Double.parseDouble(parts[5].trim())
                        : 0;

                batch.add(new HistorySlot(dcCode, ts, load, pv, price, temp,
                        ts.getDayOfWeek().getValue() <= 5));
                accepted++;
                if (min == null || ts.isBefore(min))
                {
                    min = ts;
                }
                if (max == null || ts.isAfter(max))
                {
                    max = ts;
                }
                if (batch.size() >= 2000)
                {
                    dao.batchInsertHistory(batch, true);
                    batch.clear();
                }
            }
            catch (RuntimeException e)
            {
                rejected++;
                if (errors.size() < 5)
                {
                    errors.add("第 " + lineNo + " 行解析失败：" + e.getMessage());
                }
            }
        }
        if (!batch.isEmpty())
        {
            dao.batchInsertHistory(batch, true);
        }

        int days = (min == null || max == null) ? 0 : (int) (java.time.Duration.between(min, max).toDays() + 1);
        log.info("历史数据导入完成：接受 {} 条，拒绝 {} 条，时间范围 {} ~ {}", accepted, rejected, min, max);
        return new ImportResult(accepted, rejected, days,
                min == null ? null : min.toString(), max == null ? null : max.toString(), errors);
    }

    public ImportResult importCsv(byte[] content) throws IOException
    {
        return importCsv(new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8)));
    }

    /** 历史数据概览 */
    public Map<String, Object> stats()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", dao.historyRange());
        List<Map<String, Object>> perDc = new ArrayList<>();
        for (com.ruoyi.system.dispatch.DispatchModels.DcInfo dc : dao.listDc())
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dcCode", dc.dcCode());
            row.put("dcName", dc.dcName());
            row.put("points", dao.history(dc.dcCode(),
                    LocalDateTime.of(2000, 1, 1, 0, 0),
                    LocalDateTime.of(2100, 1, 1, 0, 0)).size());
            perDc.add(row);
        }
        out.put("perDataCenter", perDc);
        return out;
    }

    private static LocalDateTime parseTime(String raw)
    {
        String s = raw.replace('T', ' ').trim();
        for (DateTimeFormatter f : TS_FORMATS)
        {
            try
            {
                return LocalDateTime.parse(raw.trim(), f);
            }
            catch (RuntimeException ignored)
            {
                // 尝试下一种格式
            }
            try
            {
                return LocalDateTime.parse(s, f);
            }
            catch (RuntimeException ignored)
            {
                // 尝试下一种格式
            }
        }
        throw new IllegalArgumentException("无法解析时间: " + raw);
    }
}
