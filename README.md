OKCoin韭菜收割机
================

这是一个在OKCoin比特币交易平台上的高频交易机器人程序，从2016年6月策略基本定型，到2017年1月中旬，这个策略成功的把最初投入的6000块钱刷到了250000。由于近日央行对比特币实行高压政策，各大平台都停止了配资，并且开始征收交易费，该策略实际上已经失效了。

 ![image](https://github.com/ShenDezhou/trading_okcoin/blob/master/img/screenshot.png?raw=true)

本机器人程序基于两个主要策略：

1. 趋势策略：在价格发生趋势性的波动时，及时下单跟进，即俗话说的**追涨杀跌**。
2. 平衡策略：仓位偏离50%时，放出小单使仓位逐渐回归50%，防止趋势末期的反转造成回撤，即**收益落袋，不吃鱼尾**。

本程序要求平衡仓位，即（本金+融资=融币），使得仓位在50%时净资产不随着价格波动，也保证了发生趋势性波动时**涨跌都赚**。

感谢以下两个项目：

* https://github.com/sutra/okcoin-client
* https://github.com/timmolter/xchange

感谢OKCoin：

* https://www.okcoin.cn

BTC: 3QFn1qfZMhMQ4FhgENR7fha3T8ZVw1bEeU

1.yum install java
2.wget https://dl.bintray.com/groovy/maven/apache-groovy-binary-2.5.0-alpha-1.zip
3.unzip apache-groovy-binary-2.5.0-alpha-1.zip
4.mv apache-groovy-binary-2.5.0-alpha-1 /usr/local/
5.PATH=$PATH:/usr/local/apache-groovy-binary-2.5.0-alpha-1/bin
6.groovy -D cfg=conf/example.cfg trading_okcoin.groovy
