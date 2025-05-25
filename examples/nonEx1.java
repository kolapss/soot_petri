public class DiningPhilosophersSynchronized {



    // Класс, инкапсулирующий общие ресурсы (блокировки) и операции над ними
    static class ResourceManager { // Сделаем static nested class для удобства в main
        // Нестатические ресурсы (объекты для блокировки)
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();

        // Метод, который будут вызывать потоки для выполнения работы
        // Он обеспечивает одинаковый порядок захвата блокировок
        public void performAction(String threadName) {
            System.out.println(threadName + ": Пытается захватить lock1...");
            synchronized (lock1) {
                System.out.println(threadName + ": Захватил lock1.");
                // Имитация работы с ресурсом 1
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println(threadName + ": Прерван во время ожидания lock1.");
                }

                System.out.println(threadName + ": Пытается захватить lock2...");
                synchronized (lock2) {
                    System.out.println(threadName + ": Захватил lock2.");
                    // Имитация работы с обоими ресурсами
                    System.out.println(threadName + ": Выполняет работу с обоими ресурсами.");
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println(threadName + ": Прерван во время работы с lock2.");
                    }
                } // lock2 освобождается
                System.out.println(threadName + ": Освободил lock2.");
            } // lock1 освобождается
            System.out.println(threadName + ": Освободил lock1.");
            System.out.println(threadName + ": Завершил работу.");
        }


    }

    public static void main(String[] args) {
        // Создаем ОДИН экземпляр ResourceManager, который будет разделен между потоками
        ResourceManager resourceManager = new ResourceManager();

        // Создаем потоки, передавая им ссылку на один и тот же экземпляр ResourceManager
        // и указывая, какой метод этого экземпляра они должны вызывать.
        Thread t1 = new Thread(() -> resourceManager.performAction(Thread.currentThread().getName()), "Поток-1");
        Thread t2 = new Thread(() -> resourceManager.performAction(Thread.currentThread().getName()), "Поток-2");
        // Thread t3 = new Thread(() -> resourceManager.worker1SpecificAction(Thread.currentThread().getName()), "Поток-3 (worker1 style)");
        // Thread t4 = new Thread(() -> resourceManager.worker2SpecificAction(Thread.currentThread().getName()), "Поток-4 (worker2 style)");


        System.out.println("Запускаем потоки...");
        t1.start();
        t2.start();
        // t3.start();
        // t4.start();

        // Ожидаем завершения потоков
        try {
            t1.join();
            t2.join();
            // t3.join();
            // t4.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Главный поток прерван.");
        }

        System.out.println("Все потоки завершили работу. Deadlock'а не произошло.");
    }