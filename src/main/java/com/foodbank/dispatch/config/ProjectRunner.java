package com.foodbank.dispatch.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ProjectRunner implements CommandLineRunner {

    @Value("${spring.threads.virtual.enabled:false}")
    private boolean virtualThreads;

    @Override
    public void run(String... args) {
        String green = "\u001B[32m";
        String yellow = "\u001B[33m";
        String cyan = "\u001B[36m";
        String reset = "\u001B[0m";
        String white = "\u001B[37m";

        // 1. 你觉得好看的 ASCII LOGO 部分
        System.out.println(green + "    ____  _____  _____  ____    ____  _____  _   _  _  __" + reset);
        System.out.println(green + "   | ___||  _  ||  _  ||  _ \\  | __ )|  _  || \\ | || |/ /" + reset);
        System.out.println(green + "   | |__ | | | || | | || | | | |  _ \\| |_| ||  \\| || ' / " + reset);
        System.out.println(green + "   |  __|| | | || | | || |_| | | |_) ||  _  || |\\  || . \\ " + reset);
        System.out.println(green + "   |_|   |_____||_____||____/  |____/|_| |_||_| \\_||_|\\_\\" + reset);
        System.out.println(yellow + "         >>> COMMUNITY LBS DISPATCH SYSTEM READY <<<" + reset);

        // 2. 重新设计的“工业化”信息中心
        System.out.println(cyan + "╔═══════════════════════ SYSTEM STATUS ══════════════════════╗" + reset);

        System.out.printf(cyan + "║ " + white + "RUNTIME  " + cyan + "│ " + reset + "Spring Boot: %-8s | JDK: %-15s " + cyan + "║\n",
                "3.4.5", "21.0.10");

        System.out.printf(cyan + "║ " + white + "FEATURES " + cyan + "│ " + reset + "Virtual Threads: %-28s " + cyan + "║\n",
                (virtualThreads ? green + "[ ENABLED ]" + reset : yellow + "[ DISABLED ]" + reset));

        System.out.println(cyan + "╟────────────────────────────────────────────────────────────╢" + reset);

        System.out.printf(cyan + "║ " + white + "DATABASE " + cyan + "│ " + reset + "MySQL 8.4  " + green + "● " + reset + "Redis 7.4  " + green + "● " + reset + "RabbitMQ 4.0 " + green + "● " + cyan + "║\n");

        System.out.println(cyan + "╟────────────────────────────────────────────────────────────╢" + reset);

        System.out.printf(cyan + "║ " + white + "ACCESS   " + cyan + "│ " + yellow + "%-48s " + cyan + "║\n",
                "http://localhost:8080/api/swagger-ui.html");

        System.out.println(cyan + "╚════════════════════════════════════════════════════════════╝" + reset);
    }
}