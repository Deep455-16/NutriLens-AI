package com.nutrilens.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalsTest {

    @Test fun balancedSplitOf2000Kcal() {
        assertEquals(MacroTargets(protein = 100, carbs = 250, fat = 67), MacroSplit.BALANCED.targets(2000))
    }

    @Test fun everySplitAddsUpToTheCalorieGoal() {
        for (split in MacroSplit.entries) {
            val t = split.targets(2400)
            val kcal = t.protein * 4 + t.carbs * 4 + t.fat * 9
            assertTrue("$split -> $kcal", kcal in 2390..2410)
        }
    }

    @Test fun unknownStoredSplitFallsBackToBalanced() {
        assertEquals(MacroSplit.BALANCED, MacroSplit.fromName(null))
        assertEquals(MacroSplit.BALANCED, MacroSplit.fromName("gone"))
        assertEquals(MacroSplit.LOWER_CARB, MacroSplit.fromName("LOWER_CARB"))
    }

    @Test fun mifflinStJeorExample() {
        // Male, 20 y, 175 cm, 65 kg: BMR = 650 + 1093.75 - 100 + 5 = 1648.75; x1.375 = 2267 -> 2250.
        assertEquals(2250, CalorieGoal.suggest(Sex.MALE, 20, 175, 65, Activity.MODERATE, GoalNames.MAINTAIN))
        // Female, 30 y, 160 cm, 55 kg: BMR = 550 + 1000 - 150 - 161 = 1239; x1.2 = 1487 -> 1500.
        assertEquals(1500, CalorieGoal.suggest(Sex.FEMALE, 30, 160, 55, Activity.LOW, GoalNames.MAINTAIN))
    }

    @Test fun goalAdjustsAndStaysInSafeRange() {
        val maintain = CalorieGoal.suggest(Sex.MALE, 20, 175, 65, Activity.MODERATE, GoalNames.MAINTAIN)
        assertEquals(maintain - 500, CalorieGoal.suggest(Sex.MALE, 20, 175, 65, Activity.MODERATE, GoalNames.LOSE))
        assertEquals(maintain + 300, CalorieGoal.suggest(Sex.MALE, 20, 175, 65, Activity.MODERATE, GoalNames.GAIN))
        assertEquals(CalorieGoal.MIN, CalorieGoal.suggest(Sex.FEMALE, 90, 140, 35, Activity.LOW, GoalNames.LOSE))
        assertEquals(CalorieGoal.MAX, CalorieGoal.suggest(Sex.MALE, 18, 210, 200, Activity.HIGH, GoalNames.GAIN))
    }
}
