#!/usr/bin/env groovy

@Grapes([
        @Grab("org.oxerr:okcoin-client-rest:3.0.0"),
        @Grab("org.slf4j:slf4j-log4j12:1.7.21"),
])
import groovy.json.JsonSlurper

import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

import org.apache.log4j.PropertyConfigurator
import org.knowm.xchange.ExchangeSpecification
import org.knowm.xchange.currency.CurrencyPair
import org.oxerr.okcoin.rest.OKCoinExchange
import org.oxerr.okcoin.rest.dto.OrderData
import org.oxerr.okcoin.rest.dto.Status
import org.oxerr.okcoin.rest.dto.Trade
import org.oxerr.okcoin.rest.dto.Type
import org.oxerr.okcoin.rest.service.polling.OKCoinAccountService
import org.oxerr.okcoin.rest.service.polling.OKCoinMarketDataService
import org.oxerr.okcoin.rest.service.polling.OKCoinTradeService
import org.slf4j.LoggerFactory

class Trading {
    def logger = LoggerFactory.getLogger(Trading.class)
    def cfg

    Trading(cfg) {
        this.cfg = cfg
    }

    def createExchange(apiKey, secKey) {
        def exchange = new OKCoinExchange()
        def exchangeSpec = new ExchangeSpecification(exchange.class)
        exchangeSpec.setApiKey(apiKey)
        exchangeSpec.setSecretKey(secKey)
        exchange.applySpecification(exchangeSpec)
        return exchange
    }

    def ignoreException = {Closure f -> try {f()} catch (all) {}}
    def start() {
        def accountExchange = createExchange(cfg.account.apikey, cfg.account.seckey) as OKCoinExchange
        def tradeExchange = createExchange(cfg.trade.apikey, cfg.trade.seckey) as OKCoinExchange
        def account = accountExchange.pollingAccountService as OKCoinAccountService
        def market = accountExchange.pollingMarketDataService as OKCoinMarketDataService
        def trader1 = tradeExchange.pollingTradeService as OKCoinTradeService
        def trader2 = tradeExchange.pollingTradeService as OKCoinTradeService
        def threadExecutor = Executors.newCachedThreadPool() as ThreadPoolExecutor
        def trading = false

        def trades
        def lastTradeId
        def vol = 0
        def updateTrades = {
            trades = market.getTrades("ltc_cny", null) as Trade[]
            vol = 0.7 * vol + 0.3 * trades.sum(0.0) {
                it.tid > lastTradeId ? it.amount : 0
            }
            lastTradeId = trades[-1].tid
            logger.info("updateTrades: vol::{}",
                        String.format("%.3f",vol))
        }
        updateTrades()


        def orderBook
        def prices = [trades[-1].price] * 15
        def bidPrice
        def askPrice
        def updateOrderBook = {
            orderBook = market.getOrderBook(CurrencyPair.LTC_CNY, 3)
            bidPrice = orderBook.bids[0].limitPrice * 0.618 + orderBook.asks[0].limitPrice * 0.382 + 0.01
            askPrice = orderBook.bids[0].limitPrice * 0.382 + orderBook.asks[0].limitPrice * 0.618 - 0.01

            prices = prices[1 .. -1] + [(
                    (orderBook.bids[0].limitPrice + orderBook.asks[0].limitPrice) / 2 * 0.7 +
                    (orderBook.bids[1].limitPrice + orderBook.asks[1].limitPrice) / 2 * 0.2 +
                    (orderBook.bids[2].limitPrice + orderBook.asks[2].limitPrice) / 2 * 0.1)]
            logger.info("updateOrderBook: prices::{}", prices)
        }
        updateOrderBook()


        def userInfo
        def ltc
        def cny
        def p = 0.5
        threadExecutor.execute {
            while (true) {
                if (trading) {
                    sleep 5
                    continue
                }
                def t = System.currentTimeMillis()
                ignoreException {

                    def orders = (
                        p < cfg.p.low ? {
                            cny -= orderBook.bids[0].limitPrice * 0.100
                            trader2.batchTrade("ltc_cny", Type.BUY, [
                                new OrderData(orderBook.bids[0].limitPrice + 0.00, 0.100G, Type.BUY),
                                //new OrderData(orderBook.bids[0].limitPrice + 0.01, 0.010G, Type.BUY),
                                //new OrderData(orderBook.bids[0].limitPrice + 0.02, 0.010G, Type.BUY),
                            ] as OrderData[])
                            logger.error("BatchTrade: {} price: {}, amount: {}, dealAmount: {}",
                                true ? '++':'--',
                                String.format("%.2f", orderBook.bids[0].limitPrice),
                                String.format("%.3f", 0.9G),
                                String.format("%.3f", 0.9G))
                        }() :
                        p > cfg.p.high ? {
                            ltc -= 0.100
                            trader2.batchTrade("ltc_cny", Type.SELL, [
                                new OrderData(orderBook.asks[0].limitPrice - 0.00, 0.100G, Type.SELL),
                                //new OrderData(orderBook.asks[0].limitPrice - 0.01, 0.010G, Type.SELL),
                                //new OrderData(orderBook.asks[0].limitPrice - 0.02, 0.010G, Type.SELL),
                            ] as OrderData[])
                            logger.error("BatchTrade: {} price: {}, amount: {}, dealAmount: {}",
                                false ? '++':'--',
                                String.format("%.2f", orderBook.asks[0].limitPrice),
                                String.format("%.3f", 0.9G),
                                String.format("%.3f", 0.9G))

                        }() :
                        null)
                    userInfo = account.userInfo
                    ltc = userInfo.info.funds.free.ltc
                    cny = userInfo.info.funds.free.cny
                    p = ltc * prices[-1] / (ltc * prices[-1] + cny)

                    if (orders != null) {
                        sleep 400
                        trader2.cancelOrder("ltc_cny", orders.orderInfo.collect {it.orderId} as long[])
                        logger.error("CANCELORDER:{}",it)
                    }
                }
                while (System.currentTimeMillis() - t < 500) {
                    sleep 5
                }
            }
        }

        threadExecutor.execute {
            while (true) {
                ignoreException {
                    trader2.openOrders.openOrders
                        .grep {it.timestamp.time - System.currentTimeMillis() < -10000}  // orders before 10s
                        .each {
                            trader2.cancelOrder(it.id)
                            logger.error("CANCELORDER:{}",it)
                        }
                }
                sleep 60000
            }
        }

        // main loop
        def ts1 = 0
        def ts0 = 0
        for (def numTick = 0; ; numTick++) {
            while (System.currentTimeMillis() - ts0 < cfg.tick.interval) {
                sleep 5
            }
            trading = false
            ts1 = ts0
            ts0 = System.currentTimeMillis()

            try {
                updateTrades()
                updateOrderBook()

                logger.warn("tick: ${ts0-ts1}, {}, net: {}, total: {}, p: {} - {}/{}, v: {}",
                        String.format("%.2f", prices[-1]),
                        String.format("%.2f", userInfo.info.funds.asset.net),
                        String.format("%.2f", userInfo.info.funds.asset.total),
                        String.format("%.2f", p),
                        String.format("%.3f", ltc),
                        String.format("%.2f", cny),
                        String.format("%.3f", vol))

                def burstPrice = prices[-1] * cfg.burst.threshold.pct
                def bull = false
                def bear = false
                def tradeAmount = 0


                if (numTick > 2 && (
                            prices[-1] - prices[-6 .. -2].max() > +burstPrice ||
                            prices[-1] - prices[-6 .. -3].max() > +burstPrice && prices[-1] > prices[-2]
                        )) {
                    bull = true
                    tradeAmount = cny / bidPrice * 0.99
                }
                if (numTick > 2 && (
                            prices[-1] - prices[-6 .. -2].min() < -burstPrice ||
                            prices[-1] - prices[-6 .. -3].min() < -burstPrice && prices[-1] < prices[-2]
                        )) {
                    bear = true
                    tradeAmount = ltc
                }


                if (vol < cfg.burst.threshold.vol) tradeAmount *= vol / cfg.burst.threshold.vol
                if (numTick < 5)  tradeAmount *= 0.80
                if (numTick < 10) tradeAmount *= 0.80
                if (bull && prices[-1] < prices[0 .. -1].max()) tradeAmount *= 0.90
                if (bear && prices[-1] > prices[0 .. -1].min()) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 2) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 3) tradeAmount *= 0.90
                if (Math.abs(prices[-1] - prices[-2]) > burstPrice * 4) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 2) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 3) tradeAmount *= 0.90
                if (orderBook.asks[0].limitPrice - orderBook.bids[0].limitPrice > burstPrice * 4) tradeAmount *= 0.90

                if (tradeAmount >= 1.0) {
                    def tradePrice = bull ? bidPrice : askPrice
                    trading = true

                    while (tradeAmount >= 1.0) {
                        def orderId = bull
                            ? trader1.trade("ltc_cny", Type.BUY,  bidPrice, tradeAmount).orderId
                            : trader1.trade("ltc_cny", Type.SELL, askPrice, tradeAmount).orderId

                        ignoreException {
                            sleep 200
                            trader1.cancelOrder("ltc_cny", orderId)
                        }


                        def order
                        while (order == null || order.status == Status.CANCEL_REQUEST_IN_PROCESS) {
                            order = trader1.getOrder("ltc_cny", orderId).orders[0]
                        }
                        logger.error("TRADING: {} price: {}, amount: {}, dealAmount: {}",
                                bull ? '++':'--',
                                String.format("%.2f", bull ? bidPrice : askPrice),
                                String.format("%.3f", tradeAmount),
                                String.format("%.3f", order.dealAmount))
                        tradeAmount -= order.dealAmount
                        tradeAmount -= 1.0
                        tradeAmount *= 0.98

                        if (order.status == Status.CANCELLED) {
                            updateOrderBook()
                            while (bull && bidPrice - tradePrice > +0.1) {
                                tradeAmount *= 0.99
                                tradePrice += 0.1
                            }
                            while (bear && askPrice - tradePrice < -0.1) {
                                tradeAmount *= 0.99
                                tradePrice -= 0.1
                            }
                        }
                    }
                    numTick = 0
                }
            } catch (InterruptedException e) {
                logger.info("interrupted: ", e)
                break

            } catch (all) {
                logger.info("unhandled exception: ", all)
                continue
            }
        }
    }
}

// configure logging
_prop = new Properties()
_prop.setProperty("log4j.rootLogger", "INFO, trading")
_prop.setProperty("log4j.appender.trading", "org.apache.log4j.ConsoleAppender")
_prop.setProperty("log4j.appender.trading.Target", "System.err")
_prop.setProperty("log4j.appender.trading.layout", "org.apache.log4j.PatternLayout")
_prop.setProperty("log4j.appender.trading.layout.ConversionPattern", "[%d{yyyy-MM-dd HH:mm:ss}] %p %m %n")
PropertyConfigurator.configure(_prop)

// start trading
println System.getProperty("java.version")
println System.getProperty("cfg")
println(new File(System.getProperty("cfg")).text)
_trading = new Trading(new ConfigSlurper().parse(new File(System.getProperty("cfg")).text))
_trading.start()
