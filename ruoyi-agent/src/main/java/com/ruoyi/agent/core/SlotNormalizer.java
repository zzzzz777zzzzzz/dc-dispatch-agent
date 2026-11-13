package com.ruoyi.agent.core;

import java.time.LocalDate;

/**
 * 槽位校正。
 * <p>
 * 站名、日期这类确定性实体，必须由代码从原文里抽，不能交给模型去猜。
 * 实测中模型会把「南区」猜成 DC-C——它不知道北/南/东/西与 DC-A..D 的对照。
 * 这里在解析之后做一次覆盖：原文里明确出现的，一律以原文为准。
 *
 * @author ruoyi
 */
public final class SlotNormalizer
{
    private static final String[][] STATIONS = {
            { "北区", "DC-A" }, { "南区", "DC-B" }, { "东区", "DC-C" }, { "西区", "DC-D" }
    };

    private SlotNormalizer()
    {
    }

    public static void normalize(Intent intent, String text, LocalDate today)
    {
        if (intent == null || text == null || text.isBlank())
        {
            return;
        }
        String station = stationOf(text);
        if (station != null)
        {
            Object modelValue = intent.slots().get("dcCode");
            if (modelValue != null && !station.equals(String.valueOf(modelValue)))
            {
                intent.withAssumption("站点按原文识别为 " + station + "（模型给的是 " + modelValue + "）");
            }
            intent.slots().put("dcCode", station);
        }
        LocalDate date = Dates.resolve(text, today);
        if (date != null)
        {
            intent.slots().put("date", date.toString());
        }
    }

    /** 从原文里识别站点编码；没提到返回 null */
    public static String stationOf(String text)
    {
        for (String[] pair : STATIONS)
        {
            if (text.contains(pair[0]))
            {
                return pair[1];
            }
        }
        String t = text.toUpperCase();
        for (char c = 'A'; c <= 'D'; c++)
        {
            if (t.contains("DC-" + c))
            {
                return "DC-" + c;
            }
            if (text.contains(c + "区") || text.contains(c + " 区"))
            {
                return "DC-" + c;
            }
        }
        return null;
    }
}
