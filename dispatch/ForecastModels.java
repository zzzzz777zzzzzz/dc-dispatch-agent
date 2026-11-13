package com.ruoyi.system.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 负荷预测模型集合。
 * <p>
 * 四个模型均为确定性算法，不做随机初始化，同一训练集重复训练结果一致。
 * 时间粒度为 15 分钟，季节周期固定为 96（一天）。
 *
 * @author ruoyi
 */
public final class ForecastModels
{
    /** 季节周期：一天 96 个 15 分钟时段 */
    public static final int SEASON = 96;

    private ForecastModels()
    {
    }

    /** 预测模型统一接口 */
    public interface Model
    {
        String code();

        /** 用训练序列拟合模型 */
        void fit(double[] y);

        /** 向后预测 horizon 个点 */
        double[] forecast(int horizon);
    }

    public static List<String> codes()
    {
        return List.of("SEASONAL_NAIVE", "MOVING_AVERAGE", "LINEAR_REGRESSION", "HOLT_WINTERS");
    }

    public static Model create(String code)
    {
        switch (code)
        {
            case "SEASONAL_NAIVE":
                return new SeasonalNaive();
            case "MOVING_AVERAGE":
                return new MovingAverage(3);
            case "LINEAR_REGRESSION":
                return new LinearRegression();
            case "HOLT_WINTERS":
                return new HoltWinters(0.35, 0.05, 0.30);
            default:
                throw new IllegalArgumentException("未知模型: " + code);
        }
    }

    // ------------------------------------------------------------------ 季节性朴素

    /** 以前一日同一时刻的负荷作为预测值，作为对照基线 */
    static final class SeasonalNaive implements Model
    {
        private double[] y;
        private int n;

        @Override
        public String code()
        {
            return "SEASONAL_NAIVE";
        }

        @Override
        public void fit(double[] series)
        {
            this.y = series;
            this.n = series.length;
        }

        @Override
        public double[] forecast(int horizon)
        {
            double[] out = new double[horizon];
            for (int h = 0; h < horizon; h++)
            {
                int idx = n - SEASON + h;
                out[h] = idx >= 0 ? y[idx] : y[n - 1];
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 同时刻移动平均

    /** 取最近 window 天同一时刻负荷的算术平均，抑制单日扰动 */
    static final class MovingAverage implements Model
    {
        private final int window;
        private double[] y;
        private int n;

        MovingAverage(int window)
        {
            this.window = window;
        }

        @Override
        public String code()
        {
            return "MOVING_AVERAGE";
        }

        @Override
        public void fit(double[] series)
        {
            this.y = series;
            this.n = series.length;
        }

        @Override
        public double[] forecast(int horizon)
        {
            double[] out = new double[horizon];
            for (int h = 0; h < horizon; h++)
            {
                double sum = 0;
                int cnt = 0;
                for (int k = 1; k <= window; k++)
                {
                    int idx = n - SEASON * k + h;
                    if (idx >= 0)
                    {
                        sum += y[idx];
                        cnt++;
                    }
                }
                out[h] = cnt == 0 ? y[n - 1] : sum / cnt;
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 三次指数平滑

    /** 加性季节项的三次指数平滑（Holt-Winters） */
    static final class HoltWinters implements Model
    {
        private final double alpha;
        private final double beta;
        private final double gamma;
        private double level;
        private double trend;
        private double[] season;
        private int n;

        HoltWinters(double alpha, double beta, double gamma)
        {
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
        }

        @Override
        public String code()
        {
            return "HOLT_WINTERS";
        }

        @Override
        public void fit(double[] y)
        {
            this.n = y.length;
            int seasons = Math.max(2, n / SEASON);

            // 逐日（逐季）均值。用全部季节估计初始水平与季节因子，
            // 避免只取首日做初始化时，单日异常或缺数就把整条预测带偏。
            double[] seasonMean = new double[seasons];
            for (int s = 0; s < seasons; s++)
            {
                double sum = 0;
                int cnt = 0;
                for (int i = 0; i < SEASON; i++)
                {
                    int idx = s * SEASON + i;
                    if (idx < n)
                    {
                        sum += y[idx];
                        cnt++;
                    }
                }
                seasonMean[s] = cnt == 0 ? 0 : sum / cnt;
            }

            level = seasonMean[0];
            trend = (seasonMean[seasons - 1] - seasonMean[0]) / Math.max(1, seasons - 1);

            // 季节因子取各季相对其季均值的偏差的平均，同样抗单日异常
            season = new double[SEASON];
            for (int i = 0; i < SEASON; i++)
            {
                double dev = 0;
                int cnt = 0;
                for (int s = 0; s < seasons; s++)
                {
                    int idx = s * SEASON + i;
                    if (idx < n)
                    {
                        dev += y[idx] - seasonMean[s];
                        cnt++;
                    }
                }
                season[i] = cnt == 0 ? 0 : dev / cnt;
            }

            for (int t = 0; t < n; t++)
            {
                int s = t % SEASON;
                double lastLevel = level;
                level = alpha * (y[t] - season[s]) + (1 - alpha) * (level + trend);
                trend = beta * (level - lastLevel) + (1 - beta) * trend;
                season[s] = gamma * (y[t] - level) + (1 - gamma) * season[s];
            }
        }

        @Override
        public double[] forecast(int horizon)
        {
            double[] out = new double[horizon];
            for (int h = 0; h < horizon; h++)
            {
                out[h] = level + (h + 1) * trend + season[(n + h) % SEASON];
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 谐波回归

    /**
     * 傅里叶谐波 + 滞后项的最小二乘回归。
     * 自变量为 1、三阶谐波的正余弦、前一日与前两日同时刻负荷，共 9 项。
     */
    static final class LinearRegression implements Model
    {
        private static final int LAG1 = SEASON;
        private static final int LAG2 = SEASON * 2;
        private static final int FEATURES = 9;

        private double[] coef;
        private double[] y;
        private int n;

        @Override
        public String code()
        {
            return "LINEAR_REGRESSION";
        }

        @Override
        public void fit(double[] series)
        {
            this.y = series;
            this.n = series.length;
            int rows = n - LAG2;
            if (rows < FEATURES * 4)
            {
                // 样本不足时退化为谐波均值模型
                coef = new double[FEATURES];
                return;
            }

            double[][] xtx = new double[FEATURES][FEATURES];
            double[] xty = new double[FEATURES];
            for (int t = LAG2; t < n; t++)
            {
                double[] x = featuresAt(t);
                for (int i = 0; i < FEATURES; i++)
                {
                    xty[i] += x[i] * y[t];
                    for (int j = 0; j < FEATURES; j++)
                    {
                        xtx[i][j] += x[i] * x[j];
                    }
                }
            }
            coef = solve(xtx, xty);
        }

        private double[] featuresAt(int t)
        {
            int slot = t % SEASON;
            double a = 2 * Math.PI * slot / SEASON;
            return new double[] {
                    1.0,
                    Math.sin(a), Math.cos(a),
                    Math.sin(2 * a), Math.cos(2 * a),
                    Math.sin(3 * a), Math.cos(3 * a),
                    y[t - LAG1],
                    y[t - LAG2]
            };
        }

        @Override
        public double[] forecast(int horizon)
        {
            double[] out = new double[horizon];
            if (coef == null)
            {
                for (int h = 0; h < horizon; h++)
                {
                    out[h] = n > 0 ? y[n - 1] : 0;
                }
                return out;
            }
            for (int h = 0; h < horizon; h++)
            {
                int slot = (n + h) % SEASON;
                double a = 2 * Math.PI * slot / SEASON;
                double[] x = {
                        1.0,
                        Math.sin(a), Math.cos(a),
                        Math.sin(2 * a), Math.cos(2 * a),
                        Math.sin(3 * a), Math.cos(3 * a),
                        y[n - LAG1 + h],
                        y[n - LAG2 + h]
                };
                double v = 0;
                for (int i = 0; i < FEATURES; i++)
                {
                    v += coef[i] * x[i];
                }
                out[h] = v;
            }
            return out;
        }

        /** 高斯消元求解对称正定方程组 */
        private static double[] solve(double[][] a, double[] b)
        {
            int n = b.length;
            double[][] m = new double[n][n + 1];
            for (int i = 0; i < n; i++)
            {
                System.arraycopy(a[i], 0, m[i], 0, n);
                m[i][n] = b[i];
            }
            for (int col = 0; col < n; col++)
            {
                int pivot = col;
                for (int r = col + 1; r < n; r++)
                {
                    if (Math.abs(m[r][col]) > Math.abs(m[pivot][col]))
                    {
                        pivot = r;
                    }
                }
                if (Math.abs(m[pivot][col]) < 1e-9)
                {
                    continue;
                }
                double[] tmp = m[col];
                m[col] = m[pivot];
                m[pivot] = tmp;

                double d = m[col][col];
                for (int c = col; c <= n; c++)
                {
                    m[col][c] /= d;
                }
                for (int r = 0; r < n; r++)
                {
                    if (r == col)
                    {
                        continue;
                    }
                    double f = m[r][col];
                    if (f == 0)
                    {
                        continue;
                    }
                    for (int c = col; c <= n; c++)
                    {
                        m[r][c] -= f * m[col][c];
                    }
                }
            }
            double[] x = new double[n];
            for (int i = 0; i < n; i++)
            {
                x[i] = m[i][n];
            }
            return x;
        }
    }

    /** 便捷方法：把负荷点序列转为 double 数组 */
    public static double[] toArray(List<Double> values)
    {
        double[] a = new double[values.size()];
        for (int i = 0; i < a.length; i++)
        {
            a[i] = values.get(i);
        }
        return a;
    }

    /** 便捷方法：构造连续序列 */
    public static double[] of(List<Double> values)
    {
        List<Double> copy = new ArrayList<>(values);
        return toArray(copy);
    }
}
