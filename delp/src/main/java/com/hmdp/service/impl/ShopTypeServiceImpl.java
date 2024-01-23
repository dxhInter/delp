package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.google.common.base.Charsets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private BloomFilter<String> bloomFilter;

    @PostConstruct
    public void init(){
        bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8), 100, 0.1);
        queryMysqlAndInitializeBloomFilter();
        log.info("布隆过滤器初始化完成");
    }
    @Override
    public List<ShopType> queryList() {
        if (!bloomFilter.mightContain(RedisConstants.CACHE_SHOP_TYPE_KEY+"1")) {
            log.error("布隆过滤器说这个键可能不存在，直接返回空集合");
            return Collections.emptyList();
        }
        // 从redis中获取数据
        List<String> shopTypeJSON = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        if (!CollectionUtil.isEmpty(shopTypeJSON)) {
            // 命中缓存
            // 将json转换为对象
            List<ShopType> shopTypes = shopTypeJSON.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return shopTypes;
        }

        // 未命中缓存 从数据库中获取
        List<ShopType> shopTypesByMySQL = query().orderByAsc("sort").list(); // 从数据库查询所有ShopType
//        if (CollectionUtil.isEmpty(shopTypesByMySQL)) {
//            // 数据库中也没有数据，发生了缓存穿透，缓存空值或者默认值
//            // 升级为布隆过滤器
//            stringRedisTemplate.opsForList().
//                    rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, CollectionUtil.newArrayList(""));
//            stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Collections.emptyList();
//        }
        // 如果存在则写入缓存再返回
        List<String> shopTypes = shopTypesByMySQL.stream()
                .sorted(Comparator.comparingInt(ShopType::getSort))
                .map(json -> JSONUtil.toJsonStr(json))
                .collect(Collectors.toList());
        // 保证顺序右插入 FIFO
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, shopTypes);
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return shopTypesByMySQL;
    }

    public void queryMysqlAndInitializeBloomFilter() {
        List<ShopType> shopTypes = query().orderByAsc("sort").list(); // 从数据库查询所有ShopType
        for (ShopType shopType : shopTypes) {
            bloomFilter.put(RedisConstants.CACHE_SHOP_TYPE_KEY+shopType.getId().toString());
        }
    }
}
