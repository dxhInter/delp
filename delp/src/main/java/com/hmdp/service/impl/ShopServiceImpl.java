package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 从redis中获取数据
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        try {
//            saveShop2Redis(id, 10L);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
        // 逻辑过期时间解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺id不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
           return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null){
            // "".equals(shopJson)
            return null;
        }
        // 实现缓存重建
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 成功获取锁，先查询redis
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 判断是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 接着查询数据库
            shop = getById(id);
            if (shop == null) {
                log.error("商铺id不存在");
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            log.error("获取锁失败",e);
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return shop;
    }

    public static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期
            return shop;
        }
        // 过期，异步更新缓存
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock){
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 判断是否存在
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            // 命中，将json反序列化为对象
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            shop = JSONUtil.toBean(data, Shop.class);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期
                return shop;
            }else {
                CACHE_REBUILD_POOL.submit(() -> {
                    try {
                        this.saveShop2Redis(id, 20L);
                    }catch (Exception e){
                        log.error("更新缓存失败",e);
                        throw new RuntimeException(e);
                    }finally {
                        unlock(lockKey);
                    }
                });
            }
        }
        // 返回店铺信息
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
           return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null){
            return null;
        }
        // 不存在 从数据库中获取数据
        Shop shop = getById(id);
        if (shop == null) {
            log.error("商铺id不存在");
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
