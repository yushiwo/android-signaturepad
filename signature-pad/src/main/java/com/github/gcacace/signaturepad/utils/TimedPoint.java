package com.github.gcacace.signaturepad.utils;

public class TimedPoint {
    public float x;
    public float y;
    public long timestamp;
    public float presure; //压力值

    public TimedPoint set(float x, float y, float presure) {
        this.x = x;
        this.y = y;
        this.timestamp = System.currentTimeMillis();
        this.presure = presure;
        return this;
    }

    /**
     * 计算两点之间的平均速度
     * @param start
     * @return
     */
    public float velocityFrom(TimedPoint start) {
        float velocity = distanceTo(start) / (this.timestamp - start.timestamp);
        if (velocity != velocity) return 0f;
        return velocity;
    }

    /**
     * 计算两点之间的距离
     * @param point
     * @return
     */
    public float distanceTo(TimedPoint point) {
        return (float) Math.sqrt(Math.pow(point.x - this.x, 2) + Math.pow(point.y - this.y, 2));
    }
}
