package org.com.sharekhan.strategy;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndicatorService {

    private static final int SUPERTREND_PERIOD = 10;
    private static final double SUPERTREND_MULTIPLIER = 3.0d;
    private static final int RSI_PERIOD = 14;
    private static final int EMA_PERIOD = 50;
    private static final int ADX_PERIOD = 14;

    public IndicatorSnapshot computeSnapshot(List<StrategyCandle> candles) {
        int last = candles.size() - 1;
        int previous = last - 1;
        double[] supertrend = computeSupertrend(candles, SUPERTREND_PERIOD, SUPERTREND_MULTIPLIER);
        double[] rsi = computeRsi(candles, RSI_PERIOD);
        double[] ema = computeEma(candles, EMA_PERIOD);
        AdxValues adx = computeAdx(candles, ADX_PERIOD);
        if (!Double.isFinite(supertrend[last])
                || !Double.isFinite(rsi[last])
                || previous < 0
                || !Double.isFinite(rsi[previous])
                || !Double.isFinite(ema[last])
                || !Double.isFinite(adx.adx()[last])
                || !Double.isFinite(adx.plusDi()[last])
                || !Double.isFinite(adx.minusDi()[last])) {
            throw new IllegalArgumentException("Indicator values are not ready for latest candle.");
        }
        return new IndicatorSnapshot(candles.get(last), supertrend[last], rsi[last], rsi[previous], ema[last],
                adx.adx()[last], adx.plusDi()[last], adx.minusDi()[last]);
    }

    public int minimumCandles() {
        return Math.max(EMA_PERIOD, ADX_PERIOD * 2 + 1);
    }

    private double[] computeSupertrend(List<StrategyCandle> candles, int period, double multiplier) {
        int n = candles.size();
        double[] atr = computeAtr(candles, period);
        double[] supertrend = filledNaN(n);
        double[] finalUpper = filledNaN(n);
        double[] finalLower = filledNaN(n);
        for (int i = period - 1; i < n; i++) {
            StrategyCandle candle = candles.get(i);
            double hl2 = (candle.high() + candle.low()) / 2.0d;
            double basicUpper = hl2 + (multiplier * atr[i]);
            double basicLower = hl2 - (multiplier * atr[i]);
            if (i == period - 1) {
                finalUpper[i] = basicUpper;
                finalLower[i] = basicLower;
                supertrend[i] = candle.close() <= basicUpper ? basicUpper : basicLower;
                continue;
            }
            StrategyCandle previous = candles.get(i - 1);
            finalUpper[i] = (basicUpper < finalUpper[i - 1] || previous.close() > finalUpper[i - 1])
                    ? basicUpper
                    : finalUpper[i - 1];
            finalLower[i] = (basicLower > finalLower[i - 1] || previous.close() < finalLower[i - 1])
                    ? basicLower
                    : finalLower[i - 1];
            if (supertrend[i - 1] == finalUpper[i - 1]) {
                supertrend[i] = candle.close() <= finalUpper[i] ? finalUpper[i] : finalLower[i];
            } else {
                supertrend[i] = candle.close() >= finalLower[i] ? finalLower[i] : finalUpper[i];
            }
        }
        return supertrend;
    }

    private double[] computeAtr(List<StrategyCandle> candles, int period) {
        int n = candles.size();
        double[] tr = new double[n];
        for (int i = 0; i < n; i++) {
            StrategyCandle candle = candles.get(i);
            if (i == 0) {
                tr[i] = candle.high() - candle.low();
            } else {
                double previousClose = candles.get(i - 1).close();
                tr[i] = Math.max(candle.high() - candle.low(),
                        Math.max(Math.abs(candle.high() - previousClose), Math.abs(candle.low() - previousClose)));
            }
        }
        double[] atr = filledNaN(n);
        if (n < period) {
            return atr;
        }
        double sum = 0d;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        atr[period - 1] = sum / period;
        for (int i = period; i < n; i++) {
            atr[i] = ((atr[i - 1] * (period - 1)) + tr[i]) / period;
        }
        return atr;
    }

    private double[] computeRsi(List<StrategyCandle> candles, int period) {
        int n = candles.size();
        double[] rsi = filledNaN(n);
        if (n <= period) {
            return rsi;
        }
        double gain = 0d;
        double loss = 0d;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            if (change >= 0d) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        double averageGain = gain / period;
        double averageLoss = loss / period;
        rsi[period] = rsiFromAverages(averageGain, averageLoss);
        for (int i = period + 1; i < n; i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            double currentGain = Math.max(change, 0d);
            double currentLoss = Math.max(-change, 0d);
            averageGain = ((averageGain * (period - 1)) + currentGain) / period;
            averageLoss = ((averageLoss * (period - 1)) + currentLoss) / period;
            rsi[i] = rsiFromAverages(averageGain, averageLoss);
        }
        return rsi;
    }

    private double rsiFromAverages(double averageGain, double averageLoss) {
        if (averageLoss == 0d) {
            return averageGain == 0d ? 50.0d : 100.0d;
        }
        double rs = averageGain / averageLoss;
        return 100.0d - (100.0d / (1.0d + rs));
    }

    private double[] computeEma(List<StrategyCandle> candles, int period) {
        int n = candles.size();
        double[] ema = filledNaN(n);
        if (n < period) {
            return ema;
        }
        double sum = 0d;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).close();
        }
        ema[period - 1] = sum / period;
        double multiplier = 2.0d / (period + 1.0d);
        for (int i = period; i < n; i++) {
            ema[i] = ((candles.get(i).close() - ema[i - 1]) * multiplier) + ema[i - 1];
        }
        return ema;
    }

    private AdxValues computeAdx(List<StrategyCandle> candles, int period) {
        int n = candles.size();
        double[] adx = filledNaN(n);
        double[] plusDi = filledNaN(n);
        double[] minusDi = filledNaN(n);
        if (n <= period * 2) {
            return new AdxValues(adx, plusDi, minusDi);
        }

        double[] tr = new double[n];
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        for (int i = 1; i < n; i++) {
            StrategyCandle current = candles.get(i);
            StrategyCandle previous = candles.get(i - 1);
            tr[i] = Math.max(current.high() - current.low(),
                    Math.max(Math.abs(current.high() - previous.close()), Math.abs(current.low() - previous.close())));
            double upMove = current.high() - previous.high();
            double downMove = previous.low() - current.low();
            plusDm[i] = upMove > downMove && upMove > 0d ? upMove : 0d;
            minusDm[i] = downMove > upMove && downMove > 0d ? downMove : 0d;
        }

        double smoothedTr = 0d;
        double smoothedPlusDm = 0d;
        double smoothedMinusDm = 0d;
        for (int i = 1; i <= period; i++) {
            smoothedTr += tr[i];
            smoothedPlusDm += plusDm[i];
            smoothedMinusDm += minusDm[i];
        }

        double[] dx = filledNaN(n);
        for (int i = period; i < n; i++) {
            if (i > period) {
                smoothedTr = smoothedTr - (smoothedTr / period) + tr[i];
                smoothedPlusDm = smoothedPlusDm - (smoothedPlusDm / period) + plusDm[i];
                smoothedMinusDm = smoothedMinusDm - (smoothedMinusDm / period) + minusDm[i];
            }
            plusDi[i] = smoothedTr == 0d ? 0d : 100.0d * (smoothedPlusDm / smoothedTr);
            minusDi[i] = smoothedTr == 0d ? 0d : 100.0d * (smoothedMinusDm / smoothedTr);
            double diSum = plusDi[i] + minusDi[i];
            dx[i] = diSum == 0d ? 0d : 100.0d * Math.abs(plusDi[i] - minusDi[i]) / diSum;
            if (i == (period * 2) - 1) {
                double dxSum = 0d;
                for (int j = period; j <= i; j++) {
                    dxSum += dx[j];
                }
                adx[i] = dxSum / period;
            } else if (i > (period * 2) - 1) {
                adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
            }
        }

        return new AdxValues(adx, plusDi, minusDi);
    }

    private double[] filledNaN(int size) {
        double[] values = new double[size];
        java.util.Arrays.fill(values, Double.NaN);
        return values;
    }

    private record AdxValues(double[] adx, double[] plusDi, double[] minusDi) {
    }
}
