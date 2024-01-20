package org.jetbrains.exposed.sql.tests.h2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.tests.TestDB
import org.jetbrains.exposed.sql.tests.shared.assertEqualLists
import org.jetbrains.exposed.sql.tests.shared.assertEquals
import org.jetbrains.exposed.sql.tests.shared.assertFalse
import org.jetbrains.exposed.sql.tests.shared.assertTrue
import org.jetbrains.exposed.sql.tests.shared.dml.DMLTestsData
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.withSuspendTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class MultiDatabaseTransactionTest {

    private val db1 by lazy {
        Database.connect(
            "jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1;", "org.h2.Driver", "root", "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }
    private val db2 by lazy {
        Database.connect(
            "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1;", "org.h2.Driver", "root", "",
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            }
        )
    }

    private var currentDB: Database? = null

    private val t1 by lazy {
        transaction(db1)
    }

    private val t2 by lazy {
        transaction(db2)
    }

    private val st1 by lazy {
        newSuspendedTransaction(db1)
    }

    private val st2 by lazy {
        newSuspendedTransaction(db2)
    }

    @Before
    fun before() {
        Assume.assumeTrue(TestDB.H2 in TestDB.enabledDialects())
        if (TransactionManager.isInitialized()) {
            currentDB = TransactionManager.currentOrNull()?.db
        }
    }

    @After
    fun after() {
        TransactionManager.resetCurrent(currentDB?.transactionManager)
    }

    @Test
    fun testTransactionWithDatabase() {
        t1 {
            assertFalse(DMLTestsData.Cities.exists())
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.exists())
            SchemaUtils.drop(DMLTestsData.Cities)
        }

        t2 {
            assertFalse(DMLTestsData.Cities.exists())
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.exists())
            SchemaUtils.drop(DMLTestsData.Cities)
        }
    }

    @Test
    fun testSimpleInsertsInDifferentDatabase() {
        t1 {
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.selectAll().empty())
            DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "city1"
            }
        }

        t2 {
            assertFalse(DMLTestsData.Cities.exists())
            SchemaUtils.create(DMLTestsData.Cities)
            DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "city2"
            }
        }

        t1 {
            assertEquals(1L, DMLTestsData.Cities.selectAll().count())
            assertEquals("city1", DMLTestsData.Cities.selectAll().single()[DMLTestsData.Cities.name])
            SchemaUtils.drop(DMLTestsData.Cities)
        }

        t2 {
            assertEquals(1L, DMLTestsData.Cities.selectAll().count())
            assertEquals("city2", DMLTestsData.Cities.selectAll().single()[DMLTestsData.Cities.name])
            SchemaUtils.drop(DMLTestsData.Cities)
        }
    }

    @Test
    fun testEmbeddedInsertsInDifferentDatabase() {
        t1 {
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.selectAll().empty())
            DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "city1"
            }

            t2 {
                assertFalse(DMLTestsData.Cities.exists())
                SchemaUtils.create(DMLTestsData.Cities)
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city2"
                }
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city3"
                }
                assertEquals(2L, DMLTestsData.Cities.selectAll().count())
                assertEquals("city3", DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.name])
                SchemaUtils.drop(DMLTestsData.Cities)
            }

            assertEquals(1L, DMLTestsData.Cities.selectAll().count())
            assertEquals("city1", DMLTestsData.Cities.selectAll().single()[DMLTestsData.Cities.name])
            SchemaUtils.drop(DMLTestsData.Cities)
        }
    }

    @Test
    fun testEmbeddedInsertsInDifferentDatabaseDepth2() {
        t1 {
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.selectAll().empty())
            DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "city1"
            }

            t2 {
                assertFalse(DMLTestsData.Cities.exists())
                SchemaUtils.create(DMLTestsData.Cities)
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city2"
                }
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city3"
                }
                assertEquals(2L, DMLTestsData.Cities.selectAll().count())
                assertEquals("city3", DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.name])

                t1 {
                    assertEquals(1L, DMLTestsData.Cities.selectAll().count())
                    DMLTestsData.Cities.insert {
                        it[DMLTestsData.Cities.name] = "city4"
                    }
                    DMLTestsData.Cities.insert {
                        it[DMLTestsData.Cities.name] = "city5"
                    }
                    assertEquals(3L, DMLTestsData.Cities.selectAll().count())
                }

                assertEquals(2L, DMLTestsData.Cities.selectAll().count())
                assertEquals("city3", DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.name])
                SchemaUtils.drop(DMLTestsData.Cities)
            }

            assertEquals(3L, DMLTestsData.Cities.selectAll().count())
            assertEqualLists(listOf("city1", "city4", "city5"), DMLTestsData.Cities.selectAll().map { it[DMLTestsData.Cities.name] })
            SchemaUtils.drop(DMLTestsData.Cities)
        }
    }

    @Test
    fun testCoroutinesWithMultiDb() = runBlocking {
        st1(Dispatchers.IO) {
            val tr1 = this
            SchemaUtils.create(DMLTestsData.Cities)
            assertTrue(DMLTestsData.Cities.selectAll().empty())
            DMLTestsData.Cities.insert {
                it[DMLTestsData.Cities.name] = "city1"
            }

            st2(Dispatchers.IO){
                assertFalse(DMLTestsData.Cities.exists())
                SchemaUtils.create(DMLTestsData.Cities)
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city2"
                }
                DMLTestsData.Cities.insert {
                    it[DMLTestsData.Cities.name] = "city3"
                }
                assertEquals(2L, DMLTestsData.Cities.selectAll().count())
                assertEquals("city3", DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.name])

                tr1.withSuspendTransaction {
                    assertEquals(1L, DMLTestsData.Cities.selectAll().count())
                    DMLTestsData.Cities.insert {
                        it[DMLTestsData.Cities.name] = "city4"
                    }
                    DMLTestsData.Cities.insert {
                        it[DMLTestsData.Cities.name] = "city5"
                    }
                    assertEquals(3L, DMLTestsData.Cities.selectAll().count())
                }

                assertEquals(2L, DMLTestsData.Cities.selectAll().count())
                assertEquals("city3", DMLTestsData.Cities.selectAll().last()[DMLTestsData.Cities.name])
                SchemaUtils.drop(DMLTestsData.Cities)
            }

            assertEquals(3L, DMLTestsData.Cities.selectAll().count())
            assertEqualLists(listOf("city1", "city4", "city5"), DMLTestsData.Cities.selectAll().map { it[DMLTestsData.Cities.name] })
            SchemaUtils.drop(DMLTestsData.Cities)
        }
    }
}
