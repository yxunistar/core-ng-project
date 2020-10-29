package core.framework.internal.redis;

import core.framework.internal.log.filter.ArrayLogParam;
import core.framework.internal.resource.PoolItem;
import core.framework.log.ActionLogContext;
import core.framework.redis.RedisSortedSet;
import core.framework.util.Maps;
import core.framework.util.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static core.framework.internal.redis.Protocol.Command.ZRANGE;
import static core.framework.internal.redis.Protocol.Command.ZRANGEBYSCORE;
import static core.framework.internal.redis.Protocol.Command.ZREM;
import static core.framework.internal.redis.Protocol.Keyword.LIMIT;
import static core.framework.internal.redis.Protocol.Keyword.NX;
import static core.framework.internal.redis.Protocol.Keyword.WITHSCORES;
import static core.framework.internal.redis.RedisEncodings.decode;
import static core.framework.internal.redis.RedisEncodings.encode;

/**
 * @author tempo
 */
public class RedisSortedSetImpl implements RedisSortedSet {
    private final Logger logger = LoggerFactory.getLogger(RedisSortedSetImpl.class);
    private final RedisImpl redis;

    RedisSortedSetImpl(RedisImpl redis) {
        this.redis = redis;
    }

    @Override
    public boolean zadd(String key, String value, long score, boolean onlyIfAbsent) {
        var watch = new StopWatch();
        boolean updated = false;
        PoolItem<RedisConnection> item = redis.pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            int length = 4 + (onlyIfAbsent ? 1 : 0);
            connection.writeArray(length);
            connection.writeBlobString(ZRANGE);
            connection.writeBlobString(encode(key));
            if (onlyIfAbsent) connection.writeBlobString(NX);
            connection.writeBlobString(encode(score));
            connection.writeBlobString(encode(value));
            connection.flush();
            String result = connection.readSimpleString();
            updated = "OK".equals(result);
            return updated;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            redis.pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, 0, updated ? 1 : 0);
            logger.debug("zadd, key={}, value={}, onlyIfAbsent={}, updated={}, elapsed={}", key, value, onlyIfAbsent, updated, elapsed);
            redis.checkSlowOperation(elapsed);
        }
    }

    @Override
    public Map<String, Long> zrange(String key, long start, long end) {
        var watch = new StopWatch();
        PoolItem<RedisConnection> item = redis.pool.borrowItem();
        Map<String, Long> values = null;
        try {
            RedisConnection connection = item.resource;
            int length = 5;
            connection.writeArray(length);
            connection.writeBlobString(ZRANGE);
            connection.writeBlobString(encode(key));
            connection.writeBlobString(encode(start));
            connection.writeBlobString(encode(end));
            connection.writeBlobString(WITHSCORES);
            connection.flush();
            Object[] response = connection.readArray();
            if (response.length % 2 != 0) throw new IOException("unexpected length of array, length=" + response.length);
            values = Maps.newHashMapWithExpectedSize(response.length / 2);
            for (int i = 0; i < response.length; i += 2) {
                values.put(decode((byte[]) response[i]), (Long) response[i + 1]);
            }
            return values;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            redis.pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, values == null ? 0 : values.size(), 0);
            logger.debug("zrange, key={}, returnedValues={}, elapsed={}", key, values, elapsed);
        }
    }

    @Override
    public Map<String, Long> zpopByScore(String key, long minScore, long maxScore, long limit) {
        var watch = new StopWatch();
        if (limit <= 0) throw new Error("limit must be greater than 0");
        if (maxScore < minScore) throw new Error("maxScore must not be smaller than minScore");

        int fetchedEntries = 0;
        Map<String, Long> values = Maps.newHashMap();
        PoolItem<RedisConnection> item = redis.pool.borrowItem();
        try {
            RedisConnection connection = item.resource;
            int length = 8;
            connection.writeArray(length);
            connection.writeBlobString(ZRANGEBYSCORE);
            connection.writeBlobString(encode(key));
            connection.writeBlobString(encode(minScore));
            connection.writeBlobString(encode(maxScore));
            connection.writeBlobString(WITHSCORES);
            connection.writeBlobString(LIMIT);
            connection.writeBlobString(encode(0L));
            connection.writeBlobString(encode(limit));
            connection.flush();
            Object[] response = connection.readArray();
            if (response == null) {
                return values;
            }
            fetchedEntries = response.length / 2;
            if (response.length % 2 != 0) throw new IOException("unexpected length of array, length=" + response.length);

            for (int i = 0; i < response.length; i += 2) {
                connection.writeKeyArgumentCommand(ZREM, key, (byte[]) response[i]);
                long l = connection.readLong();
                if (l == 1L) {
                    values.put(decode((byte[]) response[i]), (Long) response[i + 1]);
                }
            }
            return values;
        } catch (IOException e) {
            item.broken = true;
            throw new UncheckedIOException(e);
        } finally {
            redis.pool.returnItem(item);
            long elapsed = watch.elapsed();
            ActionLogContext.track("redis", elapsed, fetchedEntries, values.size());
            logger.debug("zpopByscore, key={}, size={}, poppedValues={}, elapsed={}", key, fetchedEntries, new ArrayLogParam(values.keySet().toArray(String[]::new)), elapsed);
            redis.checkSlowOperation(elapsed);
        }
    }
}
