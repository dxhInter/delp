package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandlder());
    }

    private class VoucherOrderHandlder implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    // 从消息队列中取出订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息是否成功
                    if(list == null || list.isEmpty()){
                        // 没有消息,继续循环
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 成功就处理订单，并创建
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("秒杀下单失败，处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    // 从PendingList队列中取出订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息是否成功
                    if(list == null || list.isEmpty()){
                        // pendinglist没有消息,结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 成功就处理订单，并创建
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理PendingList异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        log.error("线程休眠失败", ex);
                    }
                }
            }
        }
    }
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandlder implements Runnable {
//
//
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("秒杀下单失败，处理订单异常", e);
//                }
//            }
//        }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0){
            // 不为0说明没有秒杀资格
            return Result.fail(r == 1? "库存不足" : "一个人只能买一单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 秒杀还未开始
//            return Result.fail("秒杀还未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            // 秒杀已经结束
//            return Result.fail("秒杀已经结束");
//        }
//        if (voucher.getStock() < 1) {
//            // 库存不足
//            return Result.fail("库存不足");
//        }
//        // 这里加intern是因为如果创建的String对象在常量池中已经存在，那么就直接返回常量池中的对象，不会在堆中创建新的对象, toString()默认返回String对象
//
//        //一人一单判断
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            // 要在事物提交后释放锁
////            // spring事物失效问题，所以要使用代理对象，自己注入自己，获取spring代理对象，Aop
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            // 获取锁失败
//            return Result.fail("一个人只能买一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        int r = result.intValue();
        if (r != 0){
            // 不为0说明没有秒杀资格
            return Result.fail(r == 1? "库存不足" : "一个人只能买一单");
        }
        // 将下单消息放到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 用户已经购买过
            log.error("用户已经购买过了");
            return;
        }
        // 扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1") // stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // voucher_id = voucherId and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("扣减库存失败");
            return;
        }
        save(voucherOrder);
    }
}
