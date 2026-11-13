package com.ruoyi.agent.core;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把中文里的日期说法解析成具体日期。
 * <p>
 * 只做有限枚举，不做开放理解——调度指令里的日期错一天，计划就错一天。
 *
 * @author ruoyi
 */
public final class Dates
{
    private static final Pattern ISO = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern CN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private Dates()
    {
    }

    public static LocalDate resolve(String text, LocalDate today)
    {
        if (text == null || text.isBlank())
        {
            return null;
        }
        if (text.contains("今天") || text.contains("今日"))
        {
            return today;
        }
        if (text.contains("明天") || text.contains("明日"))
        {
            return today.plusDays(1);
        }
        if (text.contains("后天"))
        {
            return today.plusDays(2);
        }
        if (text.contains("昨天") || text.contains("昨日"))
        {
            return today.minusDays(1);
        }
        if (text.contains("前天"))
        {
            return today.minusDays(2);
        }
        Matcher m = ISO.matcher(text);
        if (m.find())
        {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        }
        m = CN.matcher(text);
        if (m.find())
        {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31)
            {
                return LocalDate.of(today.getYear(), month, day);
            }
        }
        return null;
    }

    public static String iso(LocalDate date)
    {
        return date == null ? null : date.format(FMT);
    }
}
