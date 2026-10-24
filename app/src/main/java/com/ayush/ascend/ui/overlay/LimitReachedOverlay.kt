package com.ayush.ascend.ui.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ayush.ascend.R
import com.ayush.ascend.core.Wallet
import com.ayush.ascend.ui.theme.AscendTheme
import com.ayush.ascend.ui.theme.Montserrat
import com.ayush.ascend.ui.theme.Palette

@Composable
fun LimitReachedOverlay(
    appLabel: String,
    usedMinutes: Int,
    limitMinutes: Int,
    stakeAmount: Int?,
    canConfirmPaid: Boolean,
    bonusMinutes: Int,
    onUseBonus: () -> Unit,
    onClose: () -> Unit,
    onPay: () -> Unit,
    onConfirmPaid: () -> Unit,
) = AscendTheme {
    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ascend_logo), null, Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(28.dp))
        Text("That's today's climb for $appLabel", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = Palette.Forest900, textAlign = TextAlign.Center, fontFamily = Montserrat)
        Spacer(Modifier.height(10.dp))
        Text("$usedMinutes of $limitMinutes min used. Resets at midnight.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = Palette.Forest700, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button(onClose, Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Sun, contentColor = Palette.Forest900)) {
            Text("Back to my day")
        }
        if (bonusMinutes > 0) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onUseBonus, Modifier.fillMaxWidth().height(54.dp)) {
                Text("Use ${minOf(bonusMinutes, Wallet.BONUS_CHUNK)} bonus min ($bonusMinutes left)", color = Palette.Forest900)
            }
        }
        if (stakeAmount != null) {
            Spacer(Modifier.height(12.dp))
            if (canConfirmPaid) {
                OutlinedButton(onConfirmPaid, Modifier.fillMaxWidth().height(54.dp)) { Text("I've paid ₹$stakeAmount — give me 15 min", color = Palette.Forest900) }
            } else {
                OutlinedButton(onPay, Modifier.fillMaxWidth().height(54.dp)) { Text("Pay ₹$stakeAmount to the jar for 15 min", color = Palette.Forest900) }
            }
            Text("Your accountability partner gets notified.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = Palette.Forest500, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
