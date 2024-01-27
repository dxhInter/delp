package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 超时时间，单位秒
     * @return 是否获取成功
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}
