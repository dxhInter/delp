package com.hmdp;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.base.Charsets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.junit.Test;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.query;
@Service
public class BloomTest extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService{

    @Test
    public void test(){
        BloomFilter<String> bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8),
                100, // 预计要插入的元素数量
                0.1); // 可接受的误判率
        List<ShopType> shopTypes = queryList();
        for (ShopType shopType : shopTypes) {
            bloomFilter.put(shopType.getId().toString());
        }
        System.out.println(mayContain(bloomFilter, "1"));
    }
    private boolean mayContain(BloomFilter<String> bloomFilter, String id) {
        return bloomFilter.mightContain(id);
    }

    @Override
    public List<ShopType> queryList() {
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        return shopTypes;
    }
}
