package com.blogspot.mydailyjava.weaklockfree;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A thread-safe map with weak keys. Entries are based on a key's system hash code and keys are considered
 * equal only by reference equality.
 * <p/>
 * This class does not implement the {@link java.util.Map} interface because this implementation is incompatible
 * with the map contract.
 */
public class WeakConcurrentMap<K, V> extends ReferenceQueue<K> implements Runnable {

    final ConcurrentMap<WeakKey<K>, V> target;

    /**
     * The cleaner thread or {@code null} if it does not exist.
     */
    private final Thread thread;

    /**
     * @param cleanerThread {@code true} if a thread should be started that removes stale entries.
     */
    public WeakConcurrentMap(boolean cleanerThread) {
        target = new ConcurrentHashMap<WeakKey<K>, V>();
        if (cleanerThread) {
            thread = new Thread(this);
            thread.setName("Reference cleaner thread");
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.setDaemon(true);
            thread.start();
        } else {
            thread = null;
        }
    }

    /**
     * Returns the value associated with this key or {@code null} if no such value exists.
     */
    public V get(K key) {
        if (key == null) throw new NullPointerException();
        V value = target.get(new WeakKey<K>(key));
        if (value == null) {
            value = defaultValue();
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    /**
     * Associates the given value with the provided key.
     */
    public void put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException();
        target.put(new WeakKey<K>(key, this), value);
    }

    /**
     * Removes the reference for the supplied key.
     */
    public void remove(K key) {
        if (key == null) throw new NullPointerException();
        target.remove(new WeakKey<K>(key));
    }

    /**
     * Clears the entire map.
     */
    public void clear() {
        target.clear();
    }

    /**
     * Can be overridden to provide a default value.
     */
    protected V defaultValue() {
        return null;
    }

    /**
     * @return The cleaner thread or {@code null} if no such thread was set.
     */
    public Thread getCleanerThread() {
        return thread;
    }

    @Override
    public void run() {
        try {
            while (true) {
                target.remove(remove());
            }
        } catch (InterruptedException ignored) {
            clear();
        }
    }

    /*
     * Why this works:
     * ---------------
     *
     * Note that this map only supports reference equality for keys and uses system hash codes. Also, for the
     * WeakKey instances to function correctly, we are voluntarily breaking the Java API contract for
     * hashCode/equals of these instances.
     *
     *
     * System hash codes are immutable and can therefore be computed prematurely and are stored explicitly
     * within the WeakKey instances. This way, we always know the correct hash code of a key and always
     * end up in the correct bucket of our target map. This remains true even after the weakly referenced
     * key is collected.
     *
     * If we are looking up the value of the current key via WeakConcurrentMap::get or any other public
     * API method, we know that any value associated with this key must still be in the map as the mere
     * existence of this key makes it ineligible for garbage collection. Therefore, looking up a value
     * using another WeakKey wrapper guarantees a correct result.
     *
     * If we are looking up the map entry of a WeakKey after polling it from the reference queue, we know
     * that the actual key was already collected and calling WeakKey::get returns null for both the polled
     * instance and the instance within the map. Since we explicitly stored the identity hash code for the
     * referenced value, it is however trivial to identify the correct bucket. From this bucket, the first
     * weak key with a null reference is removed. Due to hash collision, we do not know if this entry
     * represents the weak key. However, we do know that the reference queue polls at least as many weak
     * keys as there are stale map entries within the target map. If no key is ever removed from the map
     * explicitly, the reference queue eventually polls exactly as many weak keys as there are stale entries.
     *
     * Therefore, we can guarantee that there is no memory leak.
     */
    private static class WeakKey<T> extends WeakReference<T> {

        private final int hashCode;

        WeakKey(T key) {
            super(key);
            hashCode = System.identityHashCode(key);
        }

        WeakKey(T key, ReferenceQueue<? super T> queue) {
            super(key, queue);
            hashCode = System.identityHashCode(key);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            return ((WeakKey<?>) other).get() == get();
        }
    }

    /**
     * A {@link WeakConcurrentMap} where stale entries are removed as a side effect of interacting with this map.
     */
    public static class WithInlinedExpunction<K, V> extends WeakConcurrentMap<K, V> {

        public WithInlinedExpunction() {
            super(false);
        }

        @Override
        public V get(K key) {
            expungeStaleEntries();
            return super.get(key);
        }

        @Override
        public void put(K key, V value) {
            expungeStaleEntries();
            super.put(key, value);
        }

        @Override
        public void remove(K key) {
            expungeStaleEntries();
            super.remove(key);
        }

        void expungeStaleEntries() {
            Reference<?> reference;
            while ((reference = poll()) != null) {
                target.remove(reference);
            }
        }
    }
}