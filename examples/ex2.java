

import java.util.Random;

public class DiningPhilosophersSynchronized {



    public static void main(String[] args) {
        System.out.println("Программа запущена. Ожидайте deadlock...");
        Object resource1 = new Object();
        Object resource2 = new Object();
        // Поток 1
        Thread thread1 = new Thread(() -> {
            String threadName = Thread.currentThread().getName();
            try {
                System.out.println(threadName + ": Пытается захватить resource1");
                synchronized (resource1) {
                    System.out.println(threadName + ": Захватил resource1");

                    // Даем другому потоку время захватить resource2
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println(threadName + ": Прерван во время сна.");
                        return;
                    }

                    System.out.println(threadName + ": Пытается захватить resource2");
                    synchronized (resource2) {
                        System.out.println(threadName + ": Захватил resource2 (ЭТО СООБЩЕНИЕ НЕ ДОЛЖНО ПОЯВИТЬСЯ В СЛУЧАЕ DEADLOCK)");

                        // Этот блок кода, скорее всего, не будет достигнут из-за deadlock-а
                        // Но здесь мы демонстрируем использование wait/notify, как требовалось
                        System.out.println(threadName + ": Выполняет работу и будет использовать wait/notify.");
                        System.out.println(threadName + ": Уведомляет на resource1 и ждет на resource2.");
                        resource1.notifyAll(); // Уведомить тех, кто ждет на resource1
                        resource2.wait(1000);  // Ждать на resource2 (с таймаутом, чтобы программа не висела вечно если бы deadlock-а не было)
                        System.out.println(threadName + ": Завершил ожидание или таймаут на resource2.");
                    }
                    System.out.println(threadName + ": Освободил resource2");
                }
                System.out.println(threadName + ": Освободил resource1");
                System.out.println(threadName + ": Завершил работу.");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(threadName + ": Прерван во время ожидания wait().");
            }
        }, "Поток-Альфа");

        // Поток 2
        Thread thread2 = new Thread(() -> {
            String threadName = Thread.currentThread().getName();
            try {
                System.out.println(threadName + ": Пытается захватить resource2");
                synchronized (resource2) {
                    System.out.println(threadName + ": Захватил resource2");

                    // Даем другому потоку время захватить resource1
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println(threadName + ": Прерван во время сна.");
                        return;
                    }

                    System.out.println(threadName + ": Пытается захватить resource1");
                    synchronized (resource1) {
                        System.out.println(threadName + ": Захватил resource1 (ЭТО СООБЩЕНИЕ НЕ ДОЛЖНО ПОЯВИТЬСЯ В СЛУЧАЕ DEADLOCK)");

                        // Этот блок кода, скорее всего, не будет достигнут из-за deadlock-а
                        // Но здесь мы демонстрируем использование wait/notify, как требовалось
                        System.out.println(threadName + ": Выполняет работу и будет использовать wait/notify.");
                        System.out.println(threadName + ": Уведомляет на resource2 и ждет на resource1.");
                        resource2.notifyAll(); // Уведомить тех, кто ждет на resource2
                        resource1.wait(1000);  // Ждать на resource1 (с таймаутом)
                        System.out.println(threadName + ": Завершил ожидание или таймаут на resource1.");
                    }
                    System.out.println(threadName + ": Освободил resource1");
                }
                System.out.println(threadName + ": Освободил resource2");
                System.out.println(threadName + ": Завершил работу.");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println(threadName + ": Прерван во время ожидания wait().");
            }
        }, "Поток-Бета");

        // Запускаем потоки
        thread1.start();
        thread2.start();
    }
}