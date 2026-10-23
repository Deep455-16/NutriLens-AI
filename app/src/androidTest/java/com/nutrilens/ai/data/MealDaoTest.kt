package com.nutrilens.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutrilens.ai.data.local.AppDatabase
import com.nutrilens.ai.data.local.MealDao
import com.nutrilens.ai.data.local.MealEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MealDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.mealDao()
    }

    @After fun tearDown() = db.close()

    private fun meal(ts: Long, image: String? = null) = MealEntity(
        foodLabel = "pizza", foodName = "Pizza", confidence = 0.8f, calories = 285,
        protein = 12f, carbs = 36f, fat = 10f, fiber = 2.5f, servingGrams = 107,
        imageUri = image, timestamp = ts
    )

    @Test fun observeBetweenIncludesBothEnds() = runTest {
        listOf(99L, 100L, 150L, 200L, 201L).forEach { dao.insert(meal(it)) }
        val inRange = dao.observeBetween(100L, 200L).first().map { it.timestamp }
        assertEquals(listOf(200L, 150L, 100L), inRange)
    }

    @Test fun deleteAndClear() = runTest {
        val id = dao.insert(meal(1L))
        dao.insert(meal(2L))
        dao.delete(dao.getById(id)!!)
        assertNull(dao.getById(id))
        assertEquals(1, dao.observeAll().first().size)
        dao.clear()
        assertEquals(0, dao.observeAll().first().size)
    }

    @Test fun updateKeepsIdAndReplacesValues() = runTest {
        val id = dao.insert(meal(5L, "file:///a.jpg"))
        val edited = dao.getById(id)!!.copy(foodLabel = "sushi", foodName = "Sushi", calories = 570)
        dao.update(edited)
        assertEquals(edited, dao.getById(id))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test fun imageUrisSkipsMealsWithoutPhotos() = runTest {
        dao.insert(meal(1L, "file:///a.jpg"))
        dao.insert(meal(2L, null))
        assertEquals(listOf("file:///a.jpg"), dao.imageUris())
    }
}
