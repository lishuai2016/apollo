package com.ls.controller;

/**
 * @program: apollo
 * @author: lishuai
 * @create: 2019-07-02 19:50
 * - [通过spring提供的DeferredResult实现长轮询服务端推送消息](https://blog.csdn.net/xiao_jun_0820/article/details/82956593)
 */

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Collection;

@RestController
public class ApolloController {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    //guava中的Multimap，多值map,对map的增强，一个key可以保持多个value
    private Multimap<String, DeferredResult<String>> watchRequests = Multimaps.synchronizedSetMultimap(HashMultimap.create());


    //模拟长轮询
    @RequestMapping(value = "/watch/{namespace}", method = RequestMethod.GET, produces = "text/html")
    public DeferredResult<String> watch(@PathVariable("namespace") String namespace) {
        logger.info("Request received");
        DeferredResult<String> deferredResult = new DeferredResult<>();
        //当deferredResult完成时（不论是超时还是异常还是正常完成），移除watchRequests中相应的watch key
        deferredResult.onCompletion(new Runnable() {
            @Override
            public void run() {
                System.out.println("remove key:" + namespace);
                watchRequests.remove(namespace, deferredResult);
            }
        });
        watchRequests.put(namespace, deferredResult);
        logger.info("Servlet thread released");
        return deferredResult;


    }

    //模拟发布namespace配置
    @RequestMapping(value = "/publish/{namespace}", method = RequestMethod.GET, produces = "text/html")
    public Object publishConfig(@PathVariable("namespace") String namespace) {
        if (watchRequests.containsKey(namespace)) {
            Collection<DeferredResult<String>> deferredResults = watchRequests.get(namespace);
            Long time = System.currentTimeMillis();
            //通知所有watch这个namespace变更的长轮训配置变更结果
            for (DeferredResult<String> deferredResult : deferredResults) {
                deferredResult.setResult(namespace + " changed:" + time);
            }
        }
        return "success";

    }
}
