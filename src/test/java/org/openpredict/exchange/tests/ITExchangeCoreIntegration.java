package org.openpredict.exchange.tests;

import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openpredict.exchange.beans.*;
import org.openpredict.exchange.beans.api.*;
import org.openpredict.exchange.beans.cmd.CommandResultCode;
import org.openpredict.exchange.tests.util.L2MarketDataHelper;
import org.openpredict.exchange.tests.util.TestOrdersGenerator;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

@Slf4j
public class ITExchangeCoreIntegration extends IntegrationTestBase {

    @After
    public void after() throws InterruptedException {
        resetExchangeCore();
    }

    @Test(timeout = 3_000)
    public void basicFullCycleTestMargin() throws Exception {
        initExchange();
        initBasicSymbols();
        initBasicUsers();

        basicFullCycleTest(SYMBOL_MARGIN);
    }

    @Test(timeout = 3_000)
    public void basicFullCycleTestExchange() throws Exception {
        initExchange();
        initBasicSymbols();
        initBasicUsers();

        basicFullCycleTest(SYMBOL_EXCHANGE);
    }

    // TODO count/verify number of commands and events
    public void basicFullCycleTest(final int symbol) throws Exception {

        // ### 1. first user places limit orders
        final ApiPlaceOrder order101 = ApiPlaceOrder.builder().uid(UID_1).id(101).price(1600).size(7).action(OrderAction.ASK).orderType(OrderType.LIMIT).symbol(symbol).build();
        log.debug("PLACE: {}", order101);
        submitCommandSync(order101, cmd -> {
            assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS));
            assertThat(cmd.orderId, is(101L));
            assertThat(cmd.uid, is((long) UID_1));
            assertThat(cmd.price, is(1600L));
            assertThat(cmd.size, is(7L));
            assertThat(cmd.action, is(OrderAction.ASK));
            assertThat(cmd.orderType, is(OrderType.LIMIT));
            assertThat(cmd.symbol, is(symbol));
            assertNull(cmd.matcherEvent);
        });

        final ApiPlaceOrder order102 = ApiPlaceOrder.builder().uid(UID_1).id(102).price(1550).size(4).action(OrderAction.BID).orderType(OrderType.LIMIT).symbol(symbol).build();
        log.debug("PLACE: {}", order102);
        submitCommandSync(order102, cmd -> {
            assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS));
            assertNull(cmd.matcherEvent);
        });

        final L2MarketDataHelper l2helper = new L2MarketDataHelper().addAsk(1600, 7).addBid(1550, 4);
        assertEquals(l2helper.build(), requestCurrentOrderBook(symbol));


        // ### 2. second user sends market order, first order partially matched
        final ApiPlaceOrder order201 = ApiPlaceOrder.builder().uid(UID_2).id(201).size(2).action(OrderAction.BID).orderType(OrderType.MARKET).symbol(symbol).build();
        log.debug("PLACE: {}", order201);
        submitCommandSync(order201, cmd -> {
            assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS));

            List<MatcherTradeEvent> matcherEvents = cmd.extractEvents();
            assertThat(matcherEvents.size(), is(1));

            MatcherTradeEvent evt = matcherEvents.get(0);
            assertThat(evt.activeOrderId, is(201L));
            assertThat(evt.activeOrderAction, is(OrderAction.BID));
            assertThat(evt.activeOrderUid, is((long) UID_2));
            assertThat(evt.activeOrderCompleted, is(true));
            assertThat(evt.matchedOrderId, is(101L));
            assertThat(evt.matchedOrderUid, is((long) UID_1));
            assertThat(evt.matchedOrderCompleted, is(false));
            assertThat(evt.eventType, is(MatcherEventType.TRADE));
            assertThat(evt.size, is(2L));
            assertThat(evt.price, is(1600L));
        });

        // volume is decreased to 5
        l2helper.setAskVolume(0, 5);
        assertEquals(l2helper.build(), requestCurrentOrderBook(symbol));


        // ### 3. second user places limit order
        final ApiPlaceOrder order202 = ApiPlaceOrder.builder().uid(UID_2).id(202).price(1583).size(4).action(OrderAction.BID).orderType(OrderType.LIMIT).symbol(symbol).build();
        log.debug("PLACE: {}", order202);
        submitCommandSync(order202, cmd -> {
            assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS));
            assertNull(cmd.matcherEvent);
            List<MatcherTradeEvent> matcherEvents = cmd.extractEvents();
            assertThat(matcherEvents.size(), is(0));
        });

        l2helper.insertBid(0, 1583, 4);
        assertEquals(l2helper.build(), requestCurrentOrderBook(symbol));


        // ### 4. first trader moves his order - it will match existing order (202) but not entirely
        final ApiMoveOrder moveOrder = ApiMoveOrder.builder().symbol(symbol).uid(UID_1).id(101).newPrice(1580).build();
        log.debug("MOVE: {}", moveOrder);
        submitCommandSync(moveOrder, cmd -> {
            assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS));

            List<MatcherTradeEvent> matcherEvents = cmd.extractEvents();
            assertThat(matcherEvents.size(), is(1));

            MatcherTradeEvent evt = matcherEvents.get(0);
            assertThat(evt.activeOrderId, is(101L));
            assertThat(evt.activeOrderAction, is(OrderAction.ASK));
            assertThat(evt.activeOrderUid, is((long) UID_1));
            assertThat(evt.activeOrderCompleted, is(false));
            assertThat(evt.matchedOrderId, is(202L));
            assertThat(evt.matchedOrderUid, is((long) UID_2));
            assertThat(evt.matchedOrderCompleted, is(true));
            assertThat(evt.eventType, is(MatcherEventType.TRADE));
            assertThat(evt.size, is(4L));
            assertThat(evt.price, is(1583L));
        });

        l2helper.setAskPriceVolume(0, 1580, 1).removeBid(0);
        assertEquals(l2helper.build(), requestCurrentOrderBook(symbol));
    }


    @Test(timeout = 30_000)
    public void manyOperationsMargin() throws Exception {
        initExchange();
        initBasicSymbols();
        initBasicUsers();

        manyOperations(SYMBOL_MARGIN, CURRENCIES_FUTURES);
    }

    @Test(timeout = 30_000)
    public void manyOperationsExchange() throws Exception {
        initExchange();
        initBasicSymbols();
        initBasicUsers();

        manyOperations(SYMBOL_EXCHANGE, CURRENCIES_EXCHANGE);
    }

    public void manyOperations(final int symbol, final Set<Integer> currenciesAllowed) throws Exception {

        int numOrders = 1_000_000;
        int targetOrderBookOrders = 1000;
        int numUsers = 1000;

        TestOrdersGenerator.GenResult genResult = TestOrdersGenerator.generateCommands(numOrders, targetOrderBookOrders, numUsers, symbol, false);
        List<ApiCommand> apiCommands = TestOrdersGenerator.convertToApiCommand(genResult.getCommands());

        usersInit(numUsers, currenciesAllowed);

        final CountDownLatch ordersLatch = new CountDownLatch(apiCommands.size());
        consumer = cmd -> ordersLatch.countDown();
        for (ApiCommand cmd : apiCommands) {
            cmd.timestamp = System.currentTimeMillis();
            api.submitCommand(cmd);
        }
        ordersLatch.await();

        // compare orderBook final state just to make sure all commands executed same way
        // TODO compare events, wait until finish
        L2MarketData l2MarketData = requestCurrentOrderBook(symbol);
        assertEquals(genResult.getFinalOrderBookSnapshot(), l2MarketData);
        assertTrue(l2MarketData.askSize > targetOrderBookOrders / 4);
        assertTrue(l2MarketData.bidSize > targetOrderBookOrders / 4);
    }

}