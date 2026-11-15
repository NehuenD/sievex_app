package com.sievex.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sievex.app.R

/** One page of the first-run flow: what it says, and what the primary button offers. */
private data class OnboardingStep(
    @StringRes val heading: Int,
    @StringRes val body: Int,
    @StringRes val action: Int
)

/**
 * Step 3 is the record screen's introduction, so its button is the record screen's button.
 * The wording is fixed: the three cards are the whole product pitch. The pages hold resource
 * ids rather than text, so the copy is resolved in composition in the chosen language.
 */
private val onboardingSteps = listOf(
    OnboardingStep(
        heading = R.string.sys_onboarding_step1_title,
        body = R.string.sys_onboarding_step1_body,
        action = R.string.sys_onboarding_action_continue
    ),
    OnboardingStep(
        heading = R.string.sys_onboarding_step2_title,
        body = R.string.sys_onboarding_step2_body,
        action = R.string.sys_onboarding_action_continue
    ),
    OnboardingStep(
        heading = R.string.sys_onboarding_step3_title,
        body = R.string.sys_onboarding_step3_body,
        action = R.string.sys_onboarding_action_get_started
    )
)

/**
 * The first-run flow: three quiet pages, shown once.
 *
 * It is deliberately outside the app's Scaffold, so it owns the background and the system-bar
 * insets itself. The mark never changes between pages — only the words and the action do — which
 * keeps the flow reading as one screen the user is moving through, not three separate screens.
 *
 * The copy block scrolls (small phones, large font scale) while the indicator and the actions stay
 * pinned to the bottom, where the thumb already is.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    /**
     * The parent swaps this screen out on [onDone], but a double tap on the last button would
     * otherwise reach it twice. One call, ever.
     */
    var finished by rememberSaveable { mutableStateOf(false) }

    // The in-content Back button is not enough: the system gesture has to move between pages too,
    // instead of leaving the app from page 2.
    BackHandler(enabled = step > 0) { step -= 1 }

    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .systemBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Signal, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelSmall,
                color = Ink2
            )
            Spacer(Modifier.weight(1f))
            // Absent on the last page: by then the button is the only thing left to do.
            if (step < onboardingSteps.lastIndex) {
                SkipButton(onClick = finish)
            }
        }

        // Centred when the copy fits, scrolled when it does not: the min height is the viewport.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingMark()
                Spacer(Modifier.height(30.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                    },
                    label = "onboarding step"
                ) { index ->
                    val page = onboardingSteps[index]
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(page.heading),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(page.body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink2
                        )
                    }
                }
            }
        }

        StepIndicator(step)
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                BackButton { step -= 1 }
                Spacer(Modifier.width(14.dp))
            }
            PrimaryAction(
                label = stringResource(onboardingSteps[step].action),
                modifier = Modifier.weight(1f),
                onClick = { if (step < onboardingSteps.lastIndex) step += 1 else finish() }
            )
        }
    }
}

/**
 * The one drawing in the flow: waves around a handheld mic, built from shapes so it needs no
 * asset and stays crisped at any density. Hairlines only — the app has no shadows or gradients.
 */
@Composable
private fun OnboardingMark() {
    Box(
        Modifier.size(196.dp),
        contentAlignment = Alignment.Center
    ) {
        // The outer hairline stands for the burst: the same idea as the record button's rings.
        Box(
            Modifier
                .size(196.dp)
                .border(1.dp, Line.copy(alpha = 0.35f), CircleShape)
        )
        Box(
            Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Surface1)
                .border(1.dp, Line.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(SignalSoft)
                    .border(1.dp, Signal.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Mic: capsule, stem, base. Reads at 88dp without any icon dependency.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Signal)
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(8.dp)
                            .background(Signal.copy(alpha = 0.7f))
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(13.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Signal.copy(alpha = 0.85f))
                    )
                }
            }
        }
    }
}

/** Three capsules: the current one is Signal and wider, the rest are Line hairlines. */
@Composable
private fun StepIndicator(index: Int) {
    // Resolved here, not inside the semantics block: that lambda is not composable.
    val stepDescription =
        stringResource(R.string.sys_onboarding_step_indicator, index + 1, onboardingSteps.size)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = stepDescription
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        onboardingSteps.indices.forEach { i ->
            val current = i == index
            val capsuleWidth by animateDpAsState(
                targetValue = if (current) 20.dp else 8.dp,
                animationSpec = tween(200),
                label = "step capsule"
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = capsuleWidth, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (current) Signal else Line)
            )
        }
    }
}

/** Quiet top-right escape hatch. Text only, so it never competes with the primary action. */
@Composable
private fun SkipButton(onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.sys_onboarding_skip),
            style = MaterialTheme.typography.labelMedium,
            color = Ink2
        )
    }
}

/** 54dp circle, hairline border, ArrowBack. Named for TalkBack. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(Surface2)
            .border(1.dp, Line.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.ArrowBack,
            contentDescription = stringResource(R.string.sys_onboarding_back_content_description),
            tint = Ink,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The pill primary action, same shape language as the rest of the app. White on Signal. */
@Composable
private fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Signal)
            .clickable { onClick() }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1
        )
    }
}

/**
 * Where the "seen it" flag lives. One boolean, one file, written synchronously so a process death
 * straight after the last tap cannot show the flow a second time.
 */
object OnboardingPrefs {
    private const val FILE = "sievex_onboarding"
    private const val KEY = "done_v1"

    fun done(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .commit()
    }
}
