package ${configPackage};

import com.company.redis.RedisSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内部 Redis starter 联动生成配置类：引用 starter 提供的集成抽象类 RedisSupport。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisSupport redisSupport() {
        return new RedisSupport() {
        };
    }
}
