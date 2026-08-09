package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.CloudflareAPI
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NetworkTime
import com.v2ray.ang.handler.V2rayCrypt
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.AvatarGenerator
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: MutableList<ServersCache> = mutableListOf()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()
        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            
            val tvFileName = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_name)
            val tvPublisherName = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_publisher_name)
            val ivAvatar = holder.itemMainBinding.root.findViewById<ImageView>(R.id.iv_file_avatar)
            val flAvatarContainer = holder.itemMainBinding.root.findViewById<FrameLayout>(R.id.fl_avatar_container)
            val tvDataUsage = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_data_usage)
            val ivLockStatus = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_lock_status)
            val layoutIndicator = holder.itemMainBinding.root.findViewById<View>(R.id.layout_indicator)
            val tvType = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_type)
            val tvStatistics = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_statistics)
            val tvTestResult = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_test_result)
            val tvActiveCount = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_active_count)
            val tvExpiry = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_expiry_countdown)
            val bottomSection = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_bottom_section)
            
            val layoutAdminControl = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_admin_control)
            val layoutSubscribersBtn = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_subscribers_btn)
            val layoutShare = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_share)
            val layoutEdit = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_edit)
            val layoutRemove = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_remove)
            val layoutMore = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_more)
            
            val infoContainer = holder.itemMainBinding.root.findViewById<View>(R.id.info_container) as? ViewGroup

            val isProtected = V2rayCrypt.isProtected(context, guid)
            val isAdmin = V2rayCrypt.isAdmin(context, guid)
            val licenseId = V2rayCrypt.getLicenseId(context, guid)
            val targetId = if (licenseId.isNotEmpty() && licenseId != "LEGACY") licenseId else guid

            val myUserId = com.v2ray.ang.handler.AuthManager.getId(context)
            val myUserName = com.v2ray.ang.handler.AuthManager.getName(context)
            val myUserPfp = com.v2ray.ang.handler.AuthManager.getPfp(context)
            val myUserRole = com.v2ray.ang.handler.AuthManager.getRole(context)
            
            val isSuperAdmin = (myUserRole == "admin")
            val isOwnerOrAdmin = isAdmin || isSuperAdmin || (targetId == myUserId && myUserId.isNotEmpty())

            val prefs = context.getSharedPreferences("FileStatsPrefs", Context.MODE_PRIVATE)
            val dataUsage = prefs.getString("usage_$guid", "0.0 MB") ?: "0.0 MB"
            val hasActiveStory = prefs.getBoolean("story_$guid", false)
            val isCloudSaved = prefs.getBoolean("cloud_$guid", false)

            var finalPublisherName = prefs.getString("name_$guid", "") ?: ""
            var finalPublisherPfp = prefs.getString("pfp_$guid", "") ?: ""
            var finalIsVerified = prefs.getBoolean("verified_$guid", false)
            val pubId = prefs.getString("pubId_$guid", "") ?: ""

            if (finalPublisherName.isEmpty() || finalPublisherName == "صاحب الملف") {
                if (isOwnerOrAdmin && myUserName.isNotEmpty()) {
                    finalPublisherName = myUserName; finalPublisherPfp = myUserPfp; finalIsVerified = isSuperAdmin
                } else {
                    finalPublisherName = "صاحب الملف"
                }
            }

            val actualTargetId = when {
                pubId.isNotEmpty() -> pubId
                targetId.isNotEmpty() -> targetId
                isOwnerOrAdmin -> myUserId
                else -> ""
            }

            // ==========================================
            // 🌟 شريط التفاعل 🌟
            // ==========================================
            if (infoContainer != null) {
                var socialBar = infoContainer.findViewWithTag<LinearLayout>("social_bar_view")
                
                val viewsCount = prefs.getInt("views_$guid", 0)
                var likesCount = prefs.getInt("likes_$guid", 0)
                val commentsCount = prefs.getInt("comments_$guid", 0)
                var isLikedByMe = prefs.getBoolean("isLiked_$guid", false)

                if (socialBar == null) {
                    socialBar = LinearLayout(context).apply {
                        tag = "social_bar_view" 
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 15, 0, 15) 
                        }
                        gravity = Gravity.CENTER_VERTICAL
                        
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1AFFFFFF"))
                            cornerRadius = 50f
                            setStroke(1, Color.parseColor("#33FFFFFF"))
                        }
                        setPadding(40, 20, 40, 20) 
                    }

                    val tvViews = TextView(context).apply {
                        text = "👁 $viewsCount"
                        setTextColor(Color.parseColor("#B0B0B0"))
                        textSize = 15f 
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 50, 0)
                    }

                    val tvLikeIcon = TextView(context).apply {
                        text = if (isLikedByMe) "♥" else "♡"
                        setTextColor(if (isLikedByMe) Color.parseColor("#E91E63") else Color.parseColor("#B0B0B0"))
                        textSize = 18f 
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 10, 0)
                    }

                    val tvLikeCount = TextView(context).apply {
                        text = "$likesCount"
                        setTextColor(Color.parseColor("#E0E0E0"))
                        textSize = 15f 
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 50, 0)
                    }

                    val tvComments = TextView(context).apply {
                        text = "💬 $commentsCount"
                        setTextColor(Color.parseColor("#B0B0B0"))
                        textSize = 15f 
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    socialBar.addView(tvViews)
                    socialBar.addView(tvLikeIcon)
                    socialBar.addView(tvLikeCount)
                    socialBar.addView(tvComments)

                    infoContainer.addView(socialBar)
                }

                (socialBar.getChildAt(0) as? TextView)?.text = "👁 $viewsCount"
                
                val tvLikeIconView = socialBar.getChildAt(1) as? TextView
                val tvLikeCountView = socialBar.getChildAt(2) as? TextView

                tvLikeIconView?.apply {
                    text = if (isLikedByMe) "♥" else "♡"
                    setTextColor(if (isLikedByMe) Color.parseColor("#E91E63") else Color.parseColor("#B0B0B0"))
                    
                    setOnClickListener(null)
                    setOnClickListener {
                        isLikedByMe = !isLikedByMe
                        likesCount = if (isLikedByMe) likesCount + 1 else maxOf(0, likesCount - 1)
                        
                        text = if (isLikedByMe) "♥" else "♡"
                        setTextColor(if (isLikedByMe) Color.parseColor("#E91E63") else Color.parseColor("#B0B0B0"))
                        tvLikeCountView?.text = "$likesCount"
                        
                        prefs.edit().putBoolean("isLiked_$guid", isLikedByMe).putInt("likes_$guid", likesCount).apply()
                        coroutineScope.launch(Dispatchers.IO) {
                            CloudflareAPI.toggleLike(targetId, myUserId, isLikedByMe)
                        }
                    }
                }

                tvLikeCountView?.apply {
                    text = "$likesCount"
                    setOnClickListener(null)
                    setOnClickListener {
                        try {
                            val intent = Intent(context, Class.forName("com.v2ray.ang.ui.ConnectionsActivity"))
                            intent.putExtra("targetUserId", targetId)
                            intent.putExtra("type", "likers") 
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "حدث خطأ في فتح القائمة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val tvCommentsView = socialBar.getChildAt(3) as? TextView
                tvCommentsView?.apply {
                    text = "💬 $commentsCount"
                    setOnClickListener(null)
                    setOnClickListener {
                        try {
                            val intent = Intent(context, Class.forName("com.v2ray.ang.ui.CommentsActivity"))
                            intent.putExtra("guid", targetId)
                            intent.putExtra("isOwnerOrAdmin", isOwnerOrAdmin)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "الرجاء إضافة شاشة التعليقات الجديدة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val cloudIcon = if (isCloudSaved) "☁️" else "📱"
            tvFileName?.text = "${profile.remarks} $cloudIcon"

            tvPublisherName?.text = if (finalIsVerified) "$finalPublisherName ☑️" else finalPublisherName

            ivAvatar?.let {
                if (finalPublisherPfp.isNotEmpty()) {
                    try {
                        val b = Base64.decode(if (finalPublisherPfp.contains(",")) finalPublisherPfp.substringAfter(",") else finalPublisherPfp, Base64.DEFAULT)
                        it.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size))
                    } catch (e: Exception) {
                        it.setImageBitmap(AvatarGenerator.generateAvatar(finalPublisherName, actualTargetId))
                    }
                } else {
                    it.setImageBitmap(AvatarGenerator.generateAvatar(finalPublisherName, actualTargetId))
                }
            }

            flAvatarContainer?.let {
                if (hasActiveStory && actualTargetId.isNotEmpty()) {
                    it.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(5, Color.parseColor("#2196F3")) 
                        setColor(Color.TRANSPARENT)
                    }
                    it.setPadding(8, 8, 8, 8)
                    it.setOnClickListener {
                        try {
                            val intent = Intent(context, Class.forName("com.v2ray.ang.ui.StoryViewerActivity"))
                            intent.putExtra("targetUserId", actualTargetId) 
                            intent.putExtra("userId", myUserId.ifEmpty { actualTargetId })
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                } else {
                    it.background = null
                    it.setPadding(0, 0, 0, 0)
                    it.setOnClickListener {
                        if (actualTargetId.isNotEmpty()) {
                            try {
                                val intent = Intent(context, Class.forName("com.v2ray.ang.ui.UserProfileActivity"))
                                intent.putExtra("targetUserId", actualTargetId)
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        }
                    }
                }
            }

            if (isOwnerOrAdmin) {
                tvDataUsage?.text = "📊 الاستهلاك الكلي: $dataUsage"
                tvDataUsage?.setTextColor(Color.parseColor("#00BCD4")) 
            } else {
                tvDataUsage?.text = "استهلاك: $dataUsage"
            }
            
            if (isProtected) {
                ivLockStatus?.text = "🔒 مقفول"
                ivLockStatus?.setTextColor(Color.parseColor("#E53935"))
            } else {
                ivLockStatus?.text = "🔓 مفتوح"
                ivLockStatus?.setTextColor(Color.parseColor("#4CAF50"))
            }

            if (isProtected && !isOwnerOrAdmin) {
                tvStatistics?.visibility = View.GONE
                tvType?.text = "Secure Config" 
                (tvType?.parent as? CardView)?.setCardBackgroundColor(Color.parseColor("#D32F2F"))
            } else if (isOwnerOrAdmin) {
                tvStatistics?.visibility = View.VISIBLE
                tvStatistics?.text = getAddress(profile)
                tvType?.text = "Admin Panel"
                (tvType?.parent as? CardView)?.setCardBackgroundColor(Color.parseColor("#2196F3"))
            } else {
                tvStatistics?.visibility = View.VISIBLE
                tvStatistics?.text = getAddress(profile)
                tvType?.text = profile.configType.name
                (tvType?.parent as? CardView)?.setCardBackgroundColor(Color.parseColor("#FF5722"))
            }

            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            tvTestResult?.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                tvTestResult?.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
            } else {
                tvTestResult?.setTextColor(Color.parseColor("#00E676"))
            }

            if (isProtected || isOwnerOrAdmin) {
                val activeCount = V2rayCrypt.getActiveCount(context, guid)
                tvActiveCount?.visibility = View.VISIBLE
                
                if (isOwnerOrAdmin) {
                    tvActiveCount?.text = "👥 إجمالي المتصلين: $activeCount"
                    tvActiveCount?.setTextColor(Color.parseColor("#4CAF50")) 
                } else {
                    tvActiveCount?.text = "🟢 $activeCount"
                }
                
                tvActiveCount?.setOnClickListener {
                    if (isOwnerOrAdmin) {
                        try {
                            val intent = Intent(context, Class.forName("com.v2ray.ang.ui.FileActiveUsersActivity"))
                            intent.putExtra("guid", guid) 
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    } else {
                        Toast.makeText(context, "غير مصرح لك برؤية تفاصيل المتصلين", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                tvActiveCount?.visibility = View.GONE
            }

            val expiryTime = V2rayCrypt.getExpiryTime(context, guid)
            holder.countdownJob?.cancel()

            if ((isProtected || isOwnerOrAdmin) && expiryTime > 0L) {
                tvExpiry?.visibility = View.VISIBLE
                
                holder.countdownJob = coroutineScope.launch {
                    while (isActive) {
                        val currentTime = NetworkTime.currentTimeMillis(context)
                        val diffMs = expiryTime - currentTime
                        
                        if (diffMs > 0L) {
                            val d = diffMs / 86400000L
                            val h = (diffMs % 86400000L) / 3600000L
                            val m = (diffMs % 3600000L) / 60000L
                            
                            val timeText = when {
                                d > 0 -> "$d يوم"
                                h > 0 -> "$h ساعة"
                                m > 0 -> "$m دقيقة"
                                else -> "أقل من دقيقة"
                            }
                            
                            tvExpiry?.text = timeText
                            tvExpiry?.setTextColor(Color.parseColor("#FF9800")) 
                        } else {
                            tvExpiry?.text = "منتهي الصلاحية 🛑"
                            tvExpiry?.setTextColor(Color.parseColor("#E53935")) 
                        }
                        delay(60000L) 
                    }
                }
            } else {
                tvExpiry?.visibility = View.GONE
            }

            if (guid == MmkvManager.getSelectServer()) {
                layoutIndicator?.visibility = View.VISIBLE
                bottomSection?.setBackgroundColor(Color.parseColor("#1A4CAF50")) 
            } else {
                layoutIndicator?.visibility = View.INVISIBLE
                bottomSection?.setBackgroundColor(Color.TRANSPARENT)
            }

            if (doubleColumnDisplay) {
                layoutShare?.visibility = View.GONE
                layoutEdit?.visibility = View.GONE
                layoutRemove?.visibility = View.GONE
                layoutAdminControl?.visibility = View.GONE
                layoutSubscribersBtn?.visibility = View.GONE
                layoutMore?.visibility = View.VISIBLE

                layoutMore?.setOnClickListener {
                    handleOfflineShare(context, guid, profile, isMore = true)
                }
            } else {
                layoutMore?.visibility = View.GONE

                if (isProtected && !isOwnerOrAdmin) {
                    layoutShare?.visibility = View.GONE
                    layoutEdit?.visibility = View.GONE
                    layoutAdminControl?.visibility = View.GONE
                    layoutSubscribersBtn?.visibility = View.GONE
                } else if (isOwnerOrAdmin) {
                    layoutShare?.visibility = View.VISIBLE
                    layoutEdit?.visibility = View.VISIBLE
                    layoutAdminControl?.visibility = View.VISIBLE
                    layoutSubscribersBtn?.visibility = View.VISIBLE 
                } else {
                    layoutShare?.visibility = View.VISIBLE
                    layoutEdit?.visibility = View.VISIBLE
                    layoutAdminControl?.visibility = View.GONE
                    layoutSubscribersBtn?.visibility = View.GONE
                }

                layoutSubscribersBtn?.setOnClickListener {
                    if (context is MainActivity) context.openSubscribersPanel(guid)
                }

                layoutAdminControl?.setOnClickListener {
                    if (context is MainActivity) context.showExtendLicenseDialog(guid)
                }

                layoutShare?.setOnClickListener {
                    handleOfflineShare(context, guid, profile, isMore = false)
                }

                layoutEdit?.setOnClickListener {
                    if (isOwnerOrAdmin) {
                        val options = arrayOf("تعديل يدوي للسيرفر", "استبدال السيرفر من الحافظة (السحابة)")
                        AlertDialog.Builder(context)
                            .setTitle("تعديل كود المشتركين")
                            .setItems(options) { _, which ->
                                when (which) {
                                    0 -> adapterListener?.onEdit(guid, position, profile) 
                                    1 -> if (context is MainActivity) context.replaceAndSyncConfigFromClipboard(guid)
                                }
                            }
                            .show()
                    } else {
                        adapterListener?.onEdit(guid, position, profile)
                    }
                }
                
                layoutRemove?.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            infoContainer?.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
    }

    // 🌟 المشاركة الأوفلاين للنسخ إلى الحافظة فقط 🌟
    private fun handleOfflineShare(context: Context, guid: String, profile: ProfileItem, isMore: Boolean) {
        try {
            if (AngConfigManager.share2Clipboard(context, guid) == 0) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val configStr = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                
                if (configStr.isNotEmpty()) {
                    val expiryTimeMs = V2rayCrypt.getExpiryTime(context, guid)
                    val licenseId = V2rayCrypt.getLicenseId(context, guid).ifEmpty { guid }
                    val encryptedConf = V2rayCrypt.encrypt(configStr, expiryTimeMs, licenseId)
                    
                    clipboard.setPrimaryClip(ClipData.newPlainText("Encrypted Config", encryptedConf))
                    Toast.makeText(context, "تم التشفير والنسخ بنجاح! (أوفلاين 📱)", Toast.LENGTH_SHORT).show()
                } else {
                    adapterListener?.onShare(guid, profile, 0, isMore)
                }
            } else {
                adapterListener?.onShare(guid, profile, 0, isMore)
            }
        } catch (e: Exception) {
            adapterListener?.onShare(guid, profile, 0, isMore)
        }
    }

    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var countdownJob: Job? = null

        fun onItemSelected() {
            itemView.setBackgroundColor(Color.parseColor("#33FFFFFF")) 
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {}

    override fun onItemDismiss(position: Int) {}
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        coroutineScope.coroutineContext.cancelChildren()
    }
}
