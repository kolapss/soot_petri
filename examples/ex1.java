class DeadlockExample {


    public static void main(String[] args) {
        Object lock1 = new Object();
        Object lock2 = new Object();
        //Поток 1
        Thread thread1 = new Thread(() -> {
            synchronized (lock1) {
                System.out.println("Поток 1: Удерживает lock1...");

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Поток 1 был прерван");
                }

                System.out.println("Поток 1: Ожидает lock2...");
                synchronized (lock2) {
                    System.out.println("Поток 1: Удерживает lock1 и lock2.");
                }
                System.out.println("Поток 1: Освободил lock2."); // Не будет достигнуто
            }
            System.out.println("Поток 1: Освободил lock1."); // Не будет достигнуто
        }, "Поток-1");

        // Поток 2
        Thread thread2 = new Thread(() -> {
            synchronized (lock2) {
                System.out.println("Поток 2: Удерживает lock2...");

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Поток 2 был прерван");
                }

                System.out.println("Поток 2: Ожидает lock1...");
                synchronized (lock1) {
                    // Этот блок кода никогда не будет достигнут в случае deadlock
                    System.out.println("Поток 2: Удерживает lock1 и lock2.");
                }
                System.out.println("Поток 2: Освободил lock1."); // Не будет достигнуто
            }
            System.out.println("Поток 2: Освободил lock2."); // Не будет достигнуто
        }, "Поток-2");

        thread1.start();
        thread2.start();

        System.out.println("Главный поток: Потоки запущены. Ожидается deadlock...");

    }