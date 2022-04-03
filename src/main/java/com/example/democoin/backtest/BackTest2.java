package com.example.democoin.backtest;

import com.example.democoin.backtest.entity.ResultInfo;
import com.example.democoin.backtest.repository.ResultInfoJdbcTemplate;
import com.example.democoin.backtest.repository.ResultInfoRepository;
import com.example.democoin.backtest.service.AccountCoinWalletService;
import com.example.democoin.backtest.strategy.BidSignalParams;
import com.example.democoin.backtest.strategy.ask.AskReason;
import com.example.democoin.backtest.strategy.bid.BidReason;
import com.example.democoin.indicator.result.BollingerBands;
import com.example.democoin.upbit.db.entity.FifteenMinutesCandle;
import com.example.democoin.upbit.enums.MarketFlowType;
import com.example.democoin.upbit.service.CandleService;
import com.example.democoin.utils.IndicatorUtil;
import com.example.democoin.indicator.result.RSIs;
import com.example.democoin.backtest.entity.AccountCoinWallet;
import com.example.democoin.backtest.repository.AccountCoinWalletRepository;
import com.example.democoin.backtest.repository.BackTestOrdersRepository;
import com.example.democoin.backtest.service.BackTestOrderService;
import com.example.democoin.backtest.strategy.ask.AskStrategy;
import com.example.democoin.backtest.strategy.ask.BackTestAskSignal;
import com.example.democoin.backtest.strategy.bid.BackTestBidSignal;
import com.example.democoin.backtest.strategy.bid.BidStrategy;
import com.example.democoin.upbit.db.entity.FiveMinutesCandle;
import com.example.democoin.upbit.enums.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.example.democoin.backtest.strategy.ask.AskReason.BEAR_MARKET;
import static com.example.democoin.backtest.strategy.ask.AskReason.NO_ASK;
import static com.example.democoin.backtest.strategy.bid.BidReason.NO_BID;

/**
 * 분할매수 도입 안한 버전
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class BackTest2 {

    private final CandleService candleService;
    private final AccountCoinWalletService accountCoinWalletService;
    private final BackTestOrderService orderService;

    private final AccountCoinWalletRepository accountCoinWalletRepository;
    private final BackTestOrdersRepository backTestOrdersRepository;
    private final ResultInfoJdbcTemplate resultInfoJdbcTemplate;
    private final ResultInfoRepository resultInfoRepository;

    double balance = 1000000.0; // 잔고
    public static final int BID_SLOT = 4;
    public static final boolean IS_USE_FIFTEEN_CANDLE = false;
    public static final int STOP_LOSS = -2;
//    public static final int TRACE_BID = -2;


    public void start() {
        int page = 1;
        BidStrategy bidStrategy = BidStrategy.STRATEGY_16;
        AskStrategy askStrategy = AskStrategy.STRATEGY_3;

        LocalDateTime startDate = LocalDateTime.of(2017, 10, 1, 0, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(2022, 3, 26, 0, 0, 0);

        backTestOrdersRepository.deleteAll();
        accountCoinWalletRepository.deleteAll();

        for (MarketType marketType : MarketType.marketTypeList) {
            for(int i = 0; i < BID_SLOT; i++) {
                AccountCoinWallet wallet = AccountCoinWallet.of(marketType, balance * marketType.getPercent() / BID_SLOT);
                accountCoinWalletRepository.save(wallet);
            }
        }

        boolean over = false;
        while (!over) {
            int limit = 1;
            int offset = (page - 1) * limit;

            for (MarketType market : MarketType.marketTypeList) {
                List<FiveMinutesCandle> fiveMinutesCandles = candleService.findFiveMinutesCandlesLimitOffset(
                        market.getType(),
                        startDate,
                        limit,
                        offset
                );

                if (CollectionUtils.isEmpty(fiveMinutesCandles)) {
                    continue;
                }

                FiveMinutesCandle baseCandle = fiveMinutesCandles.get(0);
                if (Objects.isNull(baseCandle.getTimestamp())) {
                    continue;
                }

                List<FiveMinutesCandle> candles = candleService.findFiveMinutesCandlesUnderByTimestamp(market.getType(), baseCandle.getTimestamp());
                if (candles.size() < 200) {
                    log.info("해당 시간대는 캔들 200개 미만이므로 테스트할 수 없습니다. -- {}", baseCandle.getCandleDateTimeKst());
                    continue;
                }

                // targetCandle의 봉에서 매수, 매도
                FiveMinutesCandle targetCandle = candleService.nextCandle(baseCandle.getTimestamp(), baseCandle.getMarket().getType());

                List<AccountCoinWallet> wallets = accountCoinWalletRepository.findByMarket(market);
                WalletList walletList = WalletList.of(wallets);

                Double MA50 = candleService.getFiveMinuteCandlesMA(candles.get(50), 50);
                Double MA100 = candleService.getFiveMinuteCandlesMA(candles.get(100), 100);
                Double MA150 = candleService.getFiveMinuteCandlesMA(candles.get(150), 150);
                switch (judgeMarketFlowType(MA50, MA100, MA150)) {
                    case BEAR_MARKET: // 베어마켓에서는 거래 안한다.
//                        orderService.ask(targetCandle, walletList, BEAR_MARKET); // TODO 버그있음. 찾아야함.
                        wallets.forEach(wallet -> orderService.ask(targetCandle, wallet, BEAR_MARKET));
                        log.info("====== {} 베어마켓 진행중 전량 매도 / 거래 중지 ======", market.getName());
                        continue;
                }

                if (Objects.isNull(targetCandle)) {
                    log.info("{} 해당 캔들에서 종료됨", baseCandle.getCandleDateTimeKst());
                    return;
                }

                boolean isAskable = accountCoinWalletService.isAskable(walletList);
                boolean isBidable = accountCoinWalletService.isBidable(walletList);

                if (!isAskable && !isBidable) {
                    continue;
                }

                List<Double> prices = candles.stream().map(FiveMinutesCandle::getTradePrice).collect(Collectors.toUnmodifiableList());
                BollingerBands bollingerBands = IndicatorUtil.getBollingerBands(prices);
                RSIs rsi14 = IndicatorUtil.getRSI14(prices);

                if (isAskable) {
                    // 지갑들  매도
                    askProcess(askStrategy, candles, targetCandle, walletList, bollingerBands, rsi14);
                }

                if (isBidable) {
                    List<FifteenMinutesCandle> fifCandles = null;
                    RSIs fifRsi14 = null;
                    if (IS_USE_FIFTEEN_CANDLE) {
                        fifCandles = candleService.findFifteenMinutesCandlesUnderByTimestamp(market.getType(), baseCandle.getTimestamp());
                        fifRsi14 = IndicatorUtil.getRSI14(fifCandles.stream().map(FifteenMinutesCandle::getTradePrice).collect(Collectors.toUnmodifiableList()));
                    }

                    BidSignalParams params = getBidSignalParams(bidStrategy, candles, targetCandle, bollingerBands, rsi14, fifRsi14);
                    BidReason bidReason = 매수신호(params);
                    if (bidReason.isBid()) {
                        log.info("{} 현재 캔들", targetCandle.getCandleDateTimeKst());
                        orderService.bid(targetCandle, walletList.getBidableWallet(), bidReason);
                        log.info("{}% \r\n", targetCandle.getCandlePercent());
                    }
                }

                List<AccountCoinWallet> fetchWallets = accountCoinWalletService.fetchWallet(market, targetCandle.getTradePrice());

                WalletList result = WalletList.of(fetchWallets);
                printWalletInfo(result);

                // 기간 종료
                if (endDate.isBefore(targetCandle.getCandleDateTimeKst())) {
                    saveResultInfo(bidStrategy, askStrategy, startDate, endDate);
                    return;
                }
            }
            page++;
        }
    }

    private void askProcess(AskStrategy askStrategy, List<FiveMinutesCandle> candles, FiveMinutesCandle targetCandle, WalletList walletList, BollingerBands bollingerBands, RSIs rsi14) {
        for (AccountCoinWallet wallet : walletList.getAskableWallets()) {
            AskReason askReason = 매도신호(askStrategy, bollingerBands, rsi14, candles, wallet, targetCandle);
            if (askReason.isAsk()) { // 매도
                log.info("{} 현재 캔들", targetCandle.getCandleDateTimeKst());
                orderService.ask(targetCandle, wallet, askReason);
                log.info("{}% \r\n", targetCandle.getCandlePercent());
            }
        }
    }

    private BidSignalParams getBidSignalParams(BidStrategy bidStrategy,
                                               List<FiveMinutesCandle> candles,
                                               FiveMinutesCandle targetCandle,
                                               BollingerBands bollingerBands,
                                               RSIs rsi14,
                                               RSIs fifRsi14) {
        return BidSignalParams.builder()
                .bidStrategy(bidStrategy)
                .bollingerBands(bollingerBands)
                .rsi14(rsi14)
                .candles(candles)
                .targetCandle(targetCandle)
                .fifRsi14(fifRsi14)
                .build();
    }

    private void printWalletInfo(WalletList result) {
        if (result.isNotEmpty()) {
            log.info(result.getWalletSummaryInfo());
        }
    }

    private BidReason 매수신호(BidSignalParams params) {
        BollingerBands bollingerBands = params.getBollingerBands();
        RSIs rsi14 = params.getRsi14();
        List<FiveMinutesCandle> candles = params.getCandles();
        RSIs fifRsi14 = params.getFifRsi14();

        switch (params.getBidStrategy()) {
            case STRATEGY_1: // 볼린저밴드 하단 돌파 / RSI14 35 이하
                return BackTestBidSignal.strategy_1(rsi14, bollingerBands, candles.get(0));
            case STRATEGY_2:
                return BackTestBidSignal.strategy_2(bollingerBands, candles.get(0));
            case STRATEGY_3: // 단순 5분봉 음봉 3개시 매수
                return BackTestBidSignal.strategy_3(candles, candles.get(0));
            case STRATEGY_4:
                return BackTestBidSignal.strategy_4(bollingerBands, candles.get(0)); // 이전 캔들 필요
            case STRATEGY_5:
                return BackTestBidSignal.strategy_5(rsi14, candles, candles.get(0));
            case STRATEGY_6: // 5이평 10이평 골든크로스
                return BackTestBidSignal.strategy_6(bollingerBands, candles, candles.get(0));
            case STRATEGY_7: // rsi14 30 이하 / 볼린저 밴드 8개봉 수축 / 20 이평 이상
                return BackTestBidSignal.strategy_7(bollingerBands, rsi14, candles, candles.get(0));
            case STRATEGY_8: // 볼린저밴드 하단선 상향돌파 / 200 이평선 이상 또는 rsi 30 상향 돌파
                return BackTestBidSignal.strategy_8(bollingerBands, rsi14, candles, candles.get(0));
            case STRATEGY_9: // 볼린저밴드 하단선 상향돌파 / 200 이평선
                return BackTestBidSignal.strategy_9(bollingerBands, candles);
            case STRATEGY_10: // 볼린저밴드 7개봉 수축 / 200 이평 이상 or 볼린저밴드 하단선 상향돌파 / 200 이평선 돌파
                return BackTestBidSignal.strategy_10(bollingerBands, candles);
            case STRATEGY_11: // 5분봉 3틱 하락, rsi 50 이하
                return BackTestBidSignal.strategy_11(rsi14, candles);
            case STRATEGY_12: // 5분봉 3틱 하락, rsi 40 이하, 볼린저 밴드 하단선 아래
                return BackTestBidSignal.strategy_12(rsi14, bollingerBands, candles);
            case STRATEGY_13: // 5분봉 3틱 하락(개선1), rsi 30 이상 50 이하, 볼린저 밴드 하단선 아래
                return BackTestBidSignal.strategy_13(rsi14, bollingerBands, candles);
            case STRATEGY_14: // 5분봉 3틱 하락(개선2), 볼린저 밴드 하단선 아래, 15분봉 rsi 40 이하
                return BackTestBidSignal.strategy_14(bollingerBands, candles, fifRsi14);
            case STRATEGY_15: // 5분봉 3틱 하락(개선1)
                return BackTestBidSignal.strategy_15(candles);
            case STRATEGY_16: // 5분봉 3틱 하락(개선2)
                return BackTestBidSignal.strategy_16(candles);
            default:
                return NO_BID;
        }
    }

    private AskReason 매도신호(AskStrategy askStrategy, BollingerBands bollingerBands, RSIs rsi14, List<FiveMinutesCandle> candles, AccountCoinWallet wallet, FiveMinutesCandle targetCandle) {
        switch (askStrategy) {
            case STRATEGY_1:
                return BackTestAskSignal.strategy_1(wallet, rsi14, bollingerBands, candles.get(0));
            case STRATEGY_2:
                return BackTestAskSignal.strategy_2(wallet, bollingerBands, candles.get(0));
            case STRATEGY_3: // 단순 5분봉3틱수익손절매(-2% 손절, +2% 익절)
                return BackTestAskSignal.strategy_3(wallet, candles);
            case STRATEGY_4:
                return BackTestAskSignal.strategy_4(wallet, rsi14, bollingerBands, candles.get(0));
            case STRATEGY_5:
                return BackTestAskSignal.strategy_5(wallet, rsi14, candles.get(0));
            case STRATEGY_6:
                return BackTestAskSignal.strategy_6(wallet, bollingerBands, candles.get(0));
            case STRATEGY_7:
                return BackTestAskSignal.strategy_7(candles, candles.get(0));
            case STRATEGY_8:
                return BackTestAskSignal.strategy_8(wallet, bollingerBands, rsi14, candles.get(0));
            case STRATEGY_9: // 볼린저 밴드 상한선 하향돌파 또는 rsi 70 하향돌
                return BackTestAskSignal.strategy_9(wallet, bollingerBands, rsi14, candles, candles.get(0));
            case STRATEGY_10: // rsi 50 이상
                return BackTestAskSignal.strategy_10(wallet, rsi14, candles.get(0));
//            case STRATEGY_11: //
//                return BackTestAskSignal.strategy_11();
            default:
                return NO_ASK;
        }
    }

    private void saveResultInfo(BidStrategy bidStrategy, AskStrategy askStrategy, LocalDateTime startDate, LocalDateTime endDate) {
        ResultInfo resultInfo = ResultInfo.builder()
                .askStrategy(askStrategy)
                .bidStrategy(bidStrategy)
                .coinResult(resultInfoJdbcTemplate.getResultInfo())
                .positivePercent(resultInfoJdbcTemplate.getPositivePercent())
                .startDate(startDate)
                .endDate(endDate)
                .build();

        resultInfoRepository.save(resultInfo);
    }

    private static MarketFlowType judgeMarketFlowType(Double point50, Double point100, Double point150) {
        if (point50 > point100 && point100 > point150) {
            return MarketFlowType.BULL_MARKET;
        } else if (point50 < point100 && point100 < point150) {
            return MarketFlowType.BEAR_MARKET;
        } else {
            return MarketFlowType.SIDEWAYS;
        }
    }
}
