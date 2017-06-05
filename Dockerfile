from centos
ADD *.repo /etc/yum.repo.d/
RUN mkdir /root/conf/ && \
	yum update -y && \
        yum install java-1.8.0-openjdk-devel.x86_64 -y 

ADD conf/ /root/conf/
ADD trading_okcoin.groovy /root/
VOLUME ["/root"]
ADD which /usr/bin/which
ADD entrypoint.sh /root/
ADD groovy-2.5.0-alpha-1/ /usr/local/
#RUN ln -s /usr/local/groovy-2.5.0-alpha-1/bin/groovy /usr/bin/groovy
#RUN ln -s /usr/local/groovy-2.5.0-alpha-1/bin/grape /usr/bin/grape
RUN mkdir -p  /root/.groovy/grapes
ADD .groovy/grapes/ /root/.groovy/grapes/
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
CMD ["/root/entrypoint.sh"]
 
