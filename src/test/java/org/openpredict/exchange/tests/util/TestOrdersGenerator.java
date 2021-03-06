package org.openpredict.exchange.tests.util;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.openpredict.exchange.beans.*;
import org.openpredict.exchange.beans.api.ApiCancelOrder;
import org.openpredict.exchange.beans.api.ApiCommand;
import org.openpredict.exchange.beans.api.ApiMoveOrder;
import org.openpredict.exchange.beans.api.ApiPlaceOrder;
import org.openpredict.exchange.beans.cmd.CommandResultCode;
import org.openpredict.exchange.beans.cmd.OrderCommand;
import org.openpredict.exchange.beans.cmd.OrderCommandType;
import org.openpredict.exchange.core.orderbook.IOrderBook;
import org.openpredict.exchange.core.orderbook.OrderBookNaiveImpl;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertThat;

@Slf4j
public class TestOrdersGenerator {

    public static final int CENTRAL_PRICE = 100_000;
    public static final int MIN_PRICE = 50_000;
    public static final int MAX_PRICE = 150_000;
    public static final int PRICE_DEVIATION_DEFAULT = 5_000;

    public static final double CENTRAL_MOVE_ALPHA = 0.01;

    public static final int CHECK_ORDERBOOK_STAT_EVERY_NTH_COMMAND = 512;

    // TODO allow limiting max volume


    public static MultiSymbolGenResult generateMultipleSymbols(final List<CoreSymbolSpecification> coreSymbolSpecifications,
                                                     final int totalTransactionsNumber,
                                                     final int numUsers,
                                                     final int targetOrderBookOrdersTotal) {

        Set<Integer> symbols = coreSymbolSpecifications.stream().map(spec -> spec.symbolId).collect(Collectors.toSet());

        int numSymbols = symbols.size();

        int quotaLeft = totalTransactionsNumber;
        int c = 1;
        final Map<Integer, CompletableFuture<GenResult>> futures = new HashMap<>();

        for (int symbol : symbols) {
            final int commandsNum = (c < numSymbols) ? totalTransactionsNumber / numSymbols : quotaLeft;

            log.debug("Generating symbol {} : commands={}", symbol, commandsNum);
            futures.put(symbol, CompletableFuture.supplyAsync(() -> generateCommands(commandsNum, targetOrderBookOrdersTotal / numSymbols, numUsers, symbol, false)));
            quotaLeft -= commandsNum;
            c++;
        }

        final Map<Integer, GenResult> genResults = new HashMap<>();
        futures.forEach((symbol, future) -> {
            try {
                genResults.put(symbol, future.get());
            } catch (InterruptedException | ExecutionException ex) {
                throw new IllegalStateException(ex);
            }
        });

        int readyAtSequenceApproximate = genResults.values().stream().mapToInt(TestOrdersGenerator.GenResult::getOrderbooksFilledAtSequence).sum();
        log.debug("readyAtSequenceApproximate={}", readyAtSequenceApproximate);

        final List<OrderCommand> commands = TestOrdersGenerator.mergeCommands(genResults.values());
        final List<ApiCommand> apiCommandsFill = TestOrdersGenerator.convertToApiCommand(commands, 0, readyAtSequenceApproximate);
        final List<ApiCommand> apiCommandsBenchmark = TestOrdersGenerator.convertToApiCommand(commands, readyAtSequenceApproximate, commands.size());

        return MultiSymbolGenResult.builder()
                .genResults(genResults)
                .apiCommandsBenchmark(apiCommandsBenchmark)
                .apiCommandsFill(apiCommandsFill)
                .build();
    }

    public static GenResult generateCommands(
            int transactionsNumber,
            int targetOrderBookOrders,
            int numUsers,
            int symbol,
            boolean enableSlidingPrice) {

        IOrderBook orderBook = new OrderBookNaiveImpl();

        TestOrdersGeneratorSession session = new TestOrdersGeneratorSession(
                orderBook,
                targetOrderBookOrders,
                PRICE_DEVIATION_DEFAULT,
                numUsers,
                symbol,
                CENTRAL_PRICE,
                enableSlidingPrice);

        List<OrderCommand> commands = new ArrayList<>();

        int successfulCommands = 0;

        //int checkOrderBookStatEveryNthCommand = transactionsNumber / 1000;
        //checkOrderBookStatEveryNthCommand = 1 << (32 - Integer.numberOfLeadingZeros(checkOrderBookStatEveryNthCommand - 1));

        int nextSizeCheck = CHECK_ORDERBOOK_STAT_EVERY_NTH_COMMAND;

        long nextUpdateTime = 0;

        for (int i = 0; i < transactionsNumber; i++) {
            OrderCommand cmd = generateRandomOrder(session);
            if (cmd == null) {
                continue;
            }

            cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            cmd.symbol = session.symbol;
            //log.debug("{}. {}",i, cmd);

            if (IOrderBook.processCommand(orderBook, cmd) == CommandResultCode.SUCCESS) {
                successfulCommands++;
            }

            cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            commands.add(cmd);

            // process and cleanup matcher events
            cmd.processMatherEvents(ev -> matcherTradeEventEventHandler(session, ev));
            cmd.matcherEvent = null;

            if (i >= nextSizeCheck) {
                nextSizeCheck += CHECK_ORDERBOOK_STAT_EVERY_NTH_COMMAND;

                updateOrderBookSizeStat(session);

                if (System.currentTimeMillis() > nextUpdateTime) {
                    log.debug("{} ({}% done), last limit orders num: {}",
                            commands.size(), (i * 100L / transactionsNumber), session.lastOrderBookOrdersSize);
                    nextUpdateTime = System.currentTimeMillis() + 3000;
                    //log.debug("{}", orderBook.getL2MarketDataSnapshot(1000));
                }

            }
        }

        assertThat(session.orderbooksFilledAtSequence, greaterThan(0L)); // check targetOrdersFilled
        int commandsListSize = commands.size() - (int) session.orderbooksFilledAtSequence;
        log.debug("total commands: {}, post-fill commands: {}", commands.size(), commandsListSize);

        log.debug("completed:{} rejected:{} reduce:{}", session.numCompleted, session.numRejected, session.numReduced);

        log.debug("place limit: {} ({}%)", session.counterPlaceLimit, (float) session.counterPlaceLimit / (float) commandsListSize * 100.0f);
        log.debug("place market: {} ({}%)", session.counterPlaceMarket, (float) session.counterPlaceMarket / (float) commandsListSize * 100.0f);
        log.debug("cancel: {} ({}%)", session.counterCancel, (float) session.counterCancel / (float) commandsListSize * 100.0f);
        log.debug("move: {} ({}%)", session.counterMove, (float) session.counterMove / (float) commandsListSize * 100.0f);


        float succPerc = (float) successfulCommands / (float) commands.size() * 100.0f;
        float avgOrderBookSizeAsk = (float) session.orderBookSizeAskStat.stream().mapToInt(x -> x).average().orElse(0);
        float avgOrderBookSizeBid = (float) session.orderBookSizeBidStat.stream().mapToInt(x -> x).average().orElse(0);
        float avgOrdersNumInOrderBook = (float) session.orderBookNumOrdersStat.stream().mapToInt(x -> x).average().orElse(0);

        assertThat(succPerc, greaterThan(85.0f));
        if (transactionsNumber > CHECK_ORDERBOOK_STAT_EVERY_NTH_COMMAND) {
            assertThat(avgOrderBookSizeAsk, greaterThan(10.0f));
            assertThat(avgOrderBookSizeBid, greaterThan(10.0f));
            assertThat(avgOrdersNumInOrderBook, greaterThan(50.0f));
        }

        log.debug("Average order book size: ASK={} BID={} ({} samples)", avgOrderBookSizeAsk, avgOrderBookSizeBid, session.orderBookSizeBidStat.size());
        log.debug("Average limit orders number in the order book:{} (target:{})", avgOrdersNumInOrderBook, targetOrderBookOrders);
        log.debug("Commands success={}%", succPerc);

        L2MarketData l2MarketData = orderBook.getL2MarketDataSnapshot(-1);

        return GenResult.builder().commands(commands)
                .finalOrderbookHash(orderBook.hashCode())
                .finalOrderBookSnapshot(l2MarketData)
                .orderbooksFilledAtSequence((int) session.orderbooksFilledAtSequence)
                .build();
    }

    private static void updateOrderBookSizeStat(TestOrdersGeneratorSession session) {
        L2MarketData l2MarketDataSnapshot = session.orderBook.getL2MarketDataSnapshot(-1);
//                log.debug("{}", dumpOrderBook(l2MarketDataSnapshot));

        int ordersNum = session.orderBook.getOrdersNum();
        // regulating OB size
        session.lastOrderBookOrdersSize = ordersNum;

        if (session.orderbooksFilledAtSequence > 0) {
            session.orderBookSizeAskStat.add(l2MarketDataSnapshot.askSize);
            session.orderBookSizeBidStat.add(l2MarketDataSnapshot.bidSize);
            session.orderBookNumOrdersStat.add(ordersNum);
        }
    }

    private static void matcherTradeEventEventHandler(TestOrdersGeneratorSession session, MatcherTradeEvent ev) {
        if (ev.eventType == MatcherEventType.TRADE) {
            if (ev.activeOrderCompleted) {
//                log.debug("Complete active: {}", ev.activeOrderId);
                session.actualOrders.clear((int) ev.activeOrderId);
                session.numCompleted++;
            }
            if (ev.matchedOrderCompleted) {
//                log.debug("Complete matched: {}", ev.matchedOrderId);
                session.actualOrders.clear((int) ev.matchedOrderId);
                session.numCompleted++;
            }

            session.lastTradePrice = Math.min(MAX_PRICE, Math.max(MIN_PRICE, ev.price));

//            log.debug("       {}", ev.price);
            if (ev.price <= MIN_PRICE) {
//                log.debug("P>>>: {}", ev.price);
                session.priceDirection = 1;
            } else if (ev.price >= MAX_PRICE) {
//                log.debug("P<<<: {}", ev.price);
                session.priceDirection = -1;
            }

        } else if (ev.eventType == MatcherEventType.REJECTION) {
//            log.debug("Rejection: {}", ev.activeOrderId);
            session.actualOrders.clear((int) ev.activeOrderId);
            session.numRejected++;

            // update order book stat if order get rejected
            // that will trigger generator to issue more limit orders
            updateOrderBookSizeStat(session);
            //log.debug("Rejected {}", ev.activeOrderId);

        } else if (ev.eventType == MatcherEventType.REDUCE) {
//            log.debug("Reduce: {}", ev.activeOrderId);
            session.actualOrders.clear((int) ev.activeOrderId);
            session.numReduced++;
        }
    }


    private static OrderCommand generateRandomOrder(TestOrdersGeneratorSession session) {

        Random rand = session.rand;

        int lackOfOrders = session.targetOrderBookOrders - session.lastOrderBookOrdersSize;
        boolean growOrders = lackOfOrders > 0;
        if (session.orderbooksFilledAtSequence == 0 && lackOfOrders <= 0) {
            session.orderbooksFilledAtSequence = session.seq;

            session.counterPlaceMarket = 0;
            session.counterPlaceLimit = 0;
            session.counterCancel = 0;
            session.counterMove = 0;
        }

        int cmd = rand.nextInt(growOrders ? (lackOfOrders > 1000 ? 2 : 10) : 40);

        if (cmd < 2) {

            OrderAction action = (rand.nextInt(4) + session.priceDirection >= 2)
                    ? OrderAction.BID
                    : OrderAction.ASK;

            long size = 1 + rand.nextInt(6) * rand.nextInt(6) * rand.nextInt(6);

            OrderType orderType = growOrders ? OrderType.LIMIT : OrderType.MARKET;
            long uid = 1 + rand.nextInt(session.numUsers);

            OrderCommand placeCmd = OrderCommand.builder().command(OrderCommandType.PLACE_ORDER).uid(uid).orderId(session.seq).size(size)
                    .action(action).orderType(orderType).build();

            if (orderType == OrderType.LIMIT) {
                session.actualOrders.set(session.seq);

                int dev = 1 + (int) (Math.pow(rand.nextDouble(), 2) * session.priceDeviation);

                long p = 0;
                int x = 4;
                for (int i = 0; i < x; i++) {
                    p += rand.nextInt(dev);
                }
                p = p / x * 2 - dev;
                if (p > 0 ^ action == OrderAction.ASK) {
                    p = -p;
                }

                //log.debug("p={} action={}", p, action);
                int price = (int) session.lastTradePrice + (int) p;

                session.orderPrices.put(session.seq, price);
                session.orderUids.put(session.seq, uid);
                placeCmd.price = price;
                session.counterPlaceLimit++;
            } else {
                session.counterPlaceMarket++;
            }

            session.seq++;

            return placeCmd;
        }


        int orderId = rand.nextInt(session.seq);
        orderId = session.actualOrders.nextSetBit(orderId);
        if (orderId < 0) {
            return null;
        }

        long uid = session.orderUids.get(orderId);
        if (uid == 0) {
            return null;
        }

        if (cmd == 2) {
            session.actualOrders.clear(orderId);
            session.counterCancel++;
            return OrderCommand.cancel(orderId, (int) (long) uid);

        } else {

            int prevPrice = session.orderPrices.get(orderId);
            if (prevPrice == 0) {
                return null;
            }

            double priceMove = (session.lastTradePrice - prevPrice) * CENTRAL_MOVE_ALPHA;
            int priceMoveRounded;
            if (prevPrice > session.lastTradePrice) {
                priceMoveRounded = (int) Math.floor(priceMove);
            } else if (prevPrice < session.lastTradePrice) {
                priceMoveRounded = (int) Math.ceil(priceMove);
            } else {
                priceMoveRounded = rand.nextInt(2) * 2 - 1;
            }

            int newPrice = prevPrice + priceMoveRounded;
            session.counterMove++;

            return OrderCommand.update(orderId, (int) (long) uid, newPrice, 0);
        }
    }


    public static List<ApiCommand> convertToApiCommand(List<OrderCommand> commands) {
        return convertToApiCommand(commands, 0, commands.size());
    }

    public static List<ApiCommand> convertToApiCommand(List<OrderCommand> commands, int from, int to) {
        return commands.stream()
                .skip(from)
                .limit(to - from)
                .map(cmd -> {
                    switch (cmd.command) {
                        case PLACE_ORDER:
                            return ApiPlaceOrder.builder().symbol(cmd.symbol).uid(cmd.uid).id(cmd.orderId)
                                    .price(cmd.price).size(cmd.size).action(cmd.action).orderType(cmd.orderType).build();
                        case MOVE_ORDER:
                            return ApiMoveOrder.builder().symbol(cmd.symbol).uid(cmd.uid).id(cmd.orderId).newPrice(cmd.price).build();
                        case CANCEL_ORDER:
                            return ApiCancelOrder.builder().symbol(cmd.symbol).uid(cmd.uid).id(cmd.orderId).build();
                    }
                    throw new IllegalStateException("unsupported type: " + cmd.command);
                })
                .collect(Collectors.toList());
    }

    @Builder
    @Getter
    public static class GenResult {
        final private L2MarketData finalOrderBookSnapshot;
        final private int finalOrderbookHash;
        final private List<OrderCommand> commands;
        final private int orderbooksFilledAtSequence;
    }

    @Builder
    @Getter
    public static class MultiSymbolGenResult {
        final Map<Integer, TestOrdersGenerator.GenResult> genResults;
        final List<ApiCommand> apiCommandsFill;
        final List<ApiCommand> apiCommandsBenchmark;
    }


    public static List<OrderCommand> mergeCommands(final Collection<GenResult> genResultsCollection) {

        if (genResultsCollection.size() == 1) {
            return genResultsCollection.stream().findFirst().get().commands;
        }

        Random rand = new Random(1L);

        List<GenResult> genResults = new ArrayList<>(genResultsCollection);

        List<Integer> probabilityRanges = new ArrayList<>();
        List<Integer> leftCounters = new ArrayList<>();
        int totalCommands = 0;
        for (final GenResult genResult : genResults) {
            final int size = genResult.getCommands().size();
            leftCounters.add(size);
            totalCommands += size;
            probabilityRanges.add(totalCommands);
        }

        log.debug("Merging {} commands for {} different symbols: probabilityRanges: {}", totalCommands, genResults.size(), probabilityRanges);

        List<OrderCommand> res = new ArrayList<>(totalCommands);

        while (true) {

            int r = rand.nextInt(totalCommands);

            int pos = Collections.binarySearch(probabilityRanges, r);
            if (pos < 0) {
                pos = -1 - pos;
            }

            int left = leftCounters.get(pos);

            if (left > 0) {
                List<OrderCommand> commands = genResults.get(pos).getCommands();
                res.add(commands.get(commands.size() - left));
                leftCounters.set(pos, left - 1);
            } else {
                // todo remove/optimize
                if (res.size() == totalCommands) {
                    return res;
                }
            }

        }

    }

}
