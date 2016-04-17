/**
 * Database-related utility methods:
 *
 * * To run a code in a transaction, just call [db]
 * * To obtain the data source, just read the [dataSource] global property.
 * * To obtain an Extended EntityManager, just read the [extendedEntityManager] property.
 */
package com.example.pokusy.kotlinee

import org.hibernate.internal.SessionImpl
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.persistence.EntityManager
import javax.persistence.EntityManagerFactory
import javax.persistence.Persistence
import javax.persistence.TypedQuery
import javax.servlet.ServletRequestEvent
import javax.servlet.ServletRequestListener
import javax.servlet.annotation.WebListener
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("com.example.pokusy.DB")

private val entityManagerFactory: EntityManagerFactory = Persistence.createEntityManagerFactory("sample")

private object ConnectionDS: DataSource {
    override fun setLogWriter(out: PrintWriter?) = throw UnsupportedOperationException()

    override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()

    override fun getParentLogger() = throw UnsupportedOperationException()

    override fun getLogWriter() = throw UnsupportedOperationException()

    override fun getLoginTimeout() = throw UnsupportedOperationException()

    override fun isWrapperFor(iface: Class<*>?) = iface == DataSource::class.java

    override fun <T : Any?> unwrap(iface: Class<T>?) = throw UnsupportedOperationException()

    override fun getConnection(): Connection? = PersistenceContext.create().connection

    override fun getConnection(username: String?, password: String?): Connection? = connection
}

/**
 * The data source.
 */
val dataSource: DataSource = ConnectionDS

/**
 * Provides the entity manager, the JDBC connection and several utility methods.
 * @property em the entity manager reference
 */
class PersistenceContext(val em: EntityManager) : Closeable {
    companion object {
        fun create() = PersistenceContext(entityManagerFactory.createEntityManager())
    }
    /**
     * The underlying JDBC connection.
     */
    val connection: Connection by lazy(LazyThreadSafetyMode.NONE) { em.unwrap(SessionImpl::class.java).connection() }
    override fun close() {
        em.close()
    }
}

private val contexts: ThreadLocal<PersistenceContext> = ThreadLocal()

/**
 * Makes sure given block is executed in a DB transaction. When the block finishes normally, the transaction commits;
 * if the block throws any exception, the transaction is rolled back.
 *
 * Example of use: `db { em.persist() }`
 * @param block the block to run in the transaction. Builder-style provides helpful methods and values, e.g. [PersistenceContext.em]
 */
fun <R> db(block: PersistenceContext.()->R): R {
    var context = contexts.get()
    if (context != null) {
        return context.block()
    } else {
        context = PersistenceContext.create()
        try {
            contexts.set(context)
            return context.use {
                context.em.transaction.begin()
                var success = false
                val result: R
                try {
                    result = it.block()
                    success = true
                } finally {
                    if (success) {
                        context.em.transaction.commit()
                    } else {
                        try {
                            context.em.transaction.rollback()
                        } catch (t: Throwable) {
                            log.warn("Failed to rollback the transaction", t)
                        }
                    }
                }
                result
            }
        } finally {
            contexts.set(null)
        }
    }
}

/**
 * Returns all JPA entities of given type.
 * @param clazz the JPA entity class, not null.
 * @param  entity type
 * @return all classes, may be empty.
 */
inline fun <reified T: Any> EntityManager.findAll(): List<T> =
    createQuery("select b from ${T::class.java.simpleName} b", T::class.java).resultList

/**
 * Finds given JPA entity. Fails if there is no such entity.
 * @param id the entity id
 * @return the JPA instance, not null.
 */
inline fun <reified T: Any> EntityManager.findById(id: Any): T =
        find(T::class.java, id) ?: throw IllegalArgumentException("Parameter id: invalid value $id: no such ${T::class.java.simpleName}")

/**
 * Deletes given entity.
 * @param id entity id
 * @return true if the entity was deleted, false if there is no such entity.
 */
inline fun <reified T: Any> EntityManager.deleteById(id: Any): Boolean {
    return createQuery("delete from ${T::class.java.simpleName} b where b.id=:id").setParameter("id", id).executeUpdate() != 0
}

/**
 * Deletes all instances of given JPA entity.
 */
inline fun <reified T: Any> EntityManager.deleteAll() {
    createQuery("delete from ${T::class.java.simpleName}", T::class.java).executeUpdate()
}

/**
 * [TypedQuery.getSingleResult] works only for simple types such as Long - it does not work when retrieving a single JPA entity.
 * @return the entity or null if no entity was found
 */
fun <T> TypedQuery<T>.single(): T? {
    val list = resultList
    if (list.size > 1) throw RuntimeException("query $this: expected 0 or 1 results but got ${list.size}")
    return list.firstOrNull()
}

/**
 * Internal, do not use. Manages and closes delegate [EntityManager]s for [extendedEntityManager]
 */
@WebListener
class ExtendedEMManager: ServletRequestListener {
    override fun requestDestroyed(sre: ServletRequestEvent?) {
        isOngoingServletRequest.set(null)
        extendedEMDelegate.get()?.apply {
            extendedEMDelegate.set(null)
            close()
        }
    }

    override fun requestInitialized(sre: ServletRequestEvent?) {
        isOngoingServletRequest.set(true)
    }

    companion object {
        internal fun getExtendedEMDelegate(): EntityManager {
            val ongoingServletRequest = isOngoingServletRequest.get() ?: false
            if (!ongoingServletRequest) throw IllegalStateException("Not called from servlet thread")
            var delegate = extendedEMDelegate.get()
            if (delegate == null) {
                delegate = PersistenceContext.create().em
                extendedEMDelegate.set(delegate)
            }
            return delegate
        }

        private val isOngoingServletRequest = ThreadLocal<Boolean>()
        private val extendedEMDelegate = ThreadLocal<EntityManager>()
    }
}

/**
 * The extended entity manager, which stays valid even after the transaction is committed and
 * the servlet request finishes. Automatically allocates and releases a delegate EntityManager.
 *
 * Can only be used from Vaadin/web servlet thread - cannot be used from async threads.
 */
val extendedEntityManager: EntityManager =
    // cannot use Kotlin delegation here: kotlin will not poll for the delegate repeatedly;
    // instead it will poll once and remember the delegate instance (the EM). The EM will get eventually
    // closed after the http request
    // finishes, which would render the delegator unusable.
    Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java),
            InvocationHandler { proxy, method, args ->
                if (method!!.name == "close") {
                    // do nothing, the underlying EM is closed automatically by the ExtendedEMManager web listener
                    return@InvocationHandler null
                }
                method.invoke(ExtendedEMManager.getExtendedEMDelegate(), *(args ?: arrayOf()))
            }) as EntityManager
