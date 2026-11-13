package com.ruoyi.agent.dispatch;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;

/**
 * 工具层公用的小工具：站点解析、数字格式化、日期解析。
 * <p>
 * 所有对外的数字都必须经过这里的格式化，保证「万元 / kW / 元每度」的口径一致，
 * 也保证回复里的每个数字都能追溯到工具。
 *
 * @author ruoyi
 */
final class DispatchToolSupport
{
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    private DispatchToolSupport()
    {
    }

    /** 站点解析：先按编码，再按名称包含，最后取第一个 */
    static DcInfo resolveDc(DispatchDao dao, String code)
    {
        List<DcInfo> all = dao.listDc();
        if (all.isEmpty())
        {
            return null;
        }
        if (code == null || code.isBlank())
        {
            return all.get(0);
        }
        DcInfo dc = dao.findDc(code);
        if (dc != null)
        {
            return dc;
        }
        for (DcInfo d : all)
        {
            if (d.dcName() != null && d.dcName().contains(code))
            {
                return d;
            }
        }
        return all.get(0);
    }

    static LocalDate parseDate(String text)
    {
        if (text == null || text.isBlank() || "null".equals(text))
        {
            return null;
        }
        try
        {
            return LocalDate.parse(text.trim());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** 策略类型的侧重点，一句话 */
    static String strategyFocus(String type)
    {
        if (type == null)
        {
            return "未标注";
        }
        return switch (type)
        {
            case "MICROGRID_DISPATCH" -> "消纳优先";
            case "LOAD_DISPATCH" -> "削峰优先";
            case "COMPREHENSIVE_DISPATCH" -> "成本优先";
            default -> "综合";
        };
    }

    /** 策略类型具体改了目标函数的哪些权重，解释用 */
    static String strategyFocusDetail(String type)
    {
        if (type == null)
        {
            return "按默认权重求解";
        }
        return switch (type)
        {
            case "MICROGRID_DISPATCH" -> "少买电：每度网购电额外按 0.15 元计价，光伏自用每度再加 0.30 元奖励，碳排权重 ×2";
            case "LOAD_DISPATCH" -> "削峰：最大需量电费权重 ×3，其余按原价";
            case "COMPREHENSIVE_DISPATCH" -> "省钱：电费、碳排、需量都按原价加权，纯经济性最优";
            default -> "按默认权重求解";
        };
    }

    /** 策略参数的一行摘要 */
    static String strategyParams(com.ruoyi.system.dispatch.DispatchModels.StrategyParams s)
    {
        return "储能 SOC 目标 " + String.format(Locale.CHINA, "%.0f%%", s.socTarget() * 100)
                + "｜消纳率下限 " + String.format(Locale.CHINA, "%.0f%%", s.maxConsumptionRatio() * 100)
                + "｜负载转移阈值 " + String.format(Locale.CHINA, "%.0f kW", s.transferThreshold());
    }

    /** 金额：超过一万用万元，保留两位 */
    static String money(Double v)
    {
        if (v == null)
        {
            return "—";
        }
        double d = v;
        if (Math.abs(d) >= 10000)
        {
            return String.format(Locale.CHINA, "%.2f 万元", d / 10000);
        }
        return String.format(Locale.CHINA, "%,.0f 元", d);
    }

    static String kw(Double v)
    {
        return v == null ? "—" : String.format(Locale.CHINA, "%,.0f kW", v);
    }

    static String pct(Double v)
    {
        return v == null ? "—" : String.format(Locale.CHINA, "%.1f%%", v * 100);
    }

    static String num(Double v, int dp)
    {
        return v == null ? "—" : String.format(Locale.CHINA, "%." + dp + "f", v);
    }

    static String md(LocalDate d)
    {
        return d == null ? "—" : d.format(MD);
    }

    static String priceOf(LoadPoint p)
    {
        return p == null ? "—" : String.format(Locale.CHINA, "%.2f 元/度", p.price());
    }
}
