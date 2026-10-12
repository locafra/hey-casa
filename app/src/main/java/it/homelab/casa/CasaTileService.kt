package it.homelab.casa

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Tile delle impostazioni rapide: apre il riquadro trasparente di ascolto (ListenActivity).
 * Serve un'activity in primo piano perché Android non lascia usare il microfono da background.
 */
class CasaTileService : TileService() {

  override fun onClick() {
    super.onClick()
    val intent =
        Intent(this, ListenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(
          PendingIntent.getActivity(
              this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
      @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
      startActivityAndCollapse(intent)
    }
  }
}
