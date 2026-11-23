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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sievex.app.R
import kotlinx.coroutines.launch

/** One page of the first-run flow: what it says, and what the primary button offers. */
private data class OnboardingStep(
    @StringRes val heading: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
    @StringRes val footnote: Int? = null
)

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
        action = R.string.sys_onboarding_action_get_started,
        footnote = R.string.sys_onboarding_permissions_hint
    )
)

/**
 * First-run flow: three swipeable pages with fixed header chrome and contextual permission priming.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingSteps.size })
    val scope = rememberCoroutineScope()
    val step = pagerState.currentPage
    var finished by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = step > 0) {
        scope.launch { pagerState.animateScrollToPage(step - 1) }
    }

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
        // Fixed 56dp height keeps vertical layout completely steady on Step 3 when Skip hides.
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 24.dp, end = 20.dp),
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
            if (step < onboardingSteps.lastIndex) {
                SkipButton(onClick = finish)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { index ->
            val page = onboardingSteps[index]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingMark(step = index)
                Spacer(Modifier.height(30.dp))
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
                    if (page.footnote != null) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = Surface1,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Line, RoundedCornerShape(16.dp))
                        ) {
                            Text(
                                stringResource(page.footnote),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }
        }

        StepIndicator(
            index = step,
            onSelect = { target ->
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        )
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                BackButton {
                    scope.launch { pagerState.animateScrollToPage(step - 1) }
                }
                Spacer(Modifier.width(14.dp))
            }
            PrimaryAction(
                label = stringResource(onboardingSteps[step].action),
                modifier = Modifier.weight(1f),
                onClick = {
                    if (step < onboardingSteps.lastIndex) {
                        scope.launch { pagerState.animateScrollToPage(step + 1) }
                    } else {
                        finish()
                    }
                }
            )
        }
    }
}

/** Step-distinct geometric emblem rendered cleanly with shapes at any density. */
@Composable
private fun OnboardingMark(step: Int) {
    val accent = when (step) {
        1 -> Blue
        2 -> Mint
        else -> Signal
    }
    val softFill = when (step) {
        1 -> Color(0xFF172638)
        2 -> Color(0xFF152D29)
        else -> SignalSoft
    }

    Box(
        Modifier.size(196.dp),
        contentAlignment = Alignment.Center
    ) {
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
                    .background(softFill)
                    .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "onboarding mark"
                ) { page ->
                    when (page) {
                        0 -> {
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
                        1 -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .width(20.dp)
                                        .height(16.dp)
                                        .border(2.5.dp, Blue, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                )
                                Box(
                                    Modifier
                                        .width(30.dp)
                                        .height(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Blue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Canvas)
                                    )
                                }
                            }
                        }
                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(Mint))
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(Mint.copy(alpha = 0.45f)))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(16.dp).clip(CircleShape).background(Mint.copy(alpha = 0.45f)))
                                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(Mint))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Three interactive capsules with a full 48dp hit height. */
@Composable
private fun StepIndicator(index: Int, onSelect: (Int) -> Unit) {
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
                targetValue = if (current) 22.dp else 8.dp,
                animationSpec = tween(200),
                label = "step capsule"
            )
            Box(
                Modifier
                    .height(48.dp)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = capsuleWidth, height = 8.dp)
                        .clip(CircleShape)
                        .background(if (current) Signal else Line)
                )
            }
        }
    }
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                stringResource(R.string.sys_onboarding_skip),
                style = MaterialTheme.typography.labelMedium,
                color = Ink2
            )
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Surface2,
        shape = CircleShape,
        modifier = Modifier
            .size(54.dp)
            .border(1.dp, Line.copy(alpha = 0.6f), CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.sys_onboarding_back_content_description),
                tint = Ink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = SignalFill,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.heightIn(min = 54.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Persists first-run completion immediately in-memory via `.apply()` and flushes asynchronously
 * so closing the intro never blocks the UI thread.
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
            .apply()
    }
}
