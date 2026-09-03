package ${basePackage};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动类。
 */
@SpringBootApplication
public class ${artifact?replace("-", " ")?split(" ")?map(s -> s?cap_first)?join("")}Application {

    public static void main(String[] args) {
        SpringApplication.run(${artifact?replace("-", " ")?split(" ")?map(s -> s?cap_first)?join("")}Application.class, args);
    }
}
