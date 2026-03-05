package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections
import kotlinx.coroutines.*

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
            holder.itemMainBinding.tvName.text = profile.remarks
            
            val isProtected = V2rayCrypt.isProtected(context, guid)

            if (isProtected) {
                holder.itemMainBinding.tvStatistics.visibility = View.GONE
                holder.itemMainBinding.tvType.text = "Secure Config" 
                (holder.itemMainBinding.tvType.parent as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(Color.parseColor("#D32F2F"))
            } else {
                holder.itemMainBinding.tvStatistics.visibility = View.VISIBLE
                holder.itemMainBinding.tvStatistics.text = getAddress(profile)
                holder.itemMainBinding.tvType.text = profile.configType.name
                (holder.itemMainBinding.tvType.parent as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(Color.parseColor("#FF5722"))
            }

            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            holder.itemMainBinding.tvTestResult.text = aff?.getTestDelayString().orEmpty()
            if ((aff?.testDelayMillis ?: 0L) < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(Color.parseColor("#00E676"))
            }

            // ================================================================
            // نظام العداد الحي المعتمد على NetworkTime لعدم اختراقه عبر وقت الجهاز
            // ================================================================
            val expiryTime = V2rayCrypt.getExpiryTime(context, guid)
            val tvExpiry = holder.itemMainBinding.root.findViewById<TextView>(R.id.tv_expiry_countdown)
            
            holder.countdownJob?.cancel()

            if (isProtected && expiryTime > 0) {
                tvExpiry?.visibility = View.VISIBLE
                
                holder.countdownJob = coroutineScope.launch {
                    while (isActive) {
                        // السحر هنا: نستخدم التوقيت المستمد من الإنترنت لضمان صحة العداد
                        val currentTime = NetworkTime.currentTimeMillis()
                        val diffMs = expiryTime - currentTime
                        
                        if (diffMs > 0) {
                            val days = diffMs / (1000 * 60 * 60 * 24)
                            val hours = (diffMs / (1000 * 60 * 60)) % 24
                            val minutes = (diffMs / (1000 * 60)) % 60
                            
                            val timeText = when {
                                days > 0 -> "$days يوم و $hours ساعة"
                                hours > 0 -> "$hours ساعة و $minutes دقيقة"
                                else -> "$minutes دقيقة"
                            }
                            
                            tvExpiry?.text = timeText
                            tvExpiry?.setTextColor(Color.parseColor("#FF9800")) 
                        } else {
                            tvExpiry?.text = "منتهي الصلاحية"
                            tvExpiry?.setTextColor(Color.parseColor("#E53935")) 
                        }
                        
                        delay(60000) // تحديث تلقائي كل دقيقة
                    }
                }
            } else {
                tvExpiry?.visibility = View.GONE
            }
            // ================================================================

            val lottieVerified = holder.itemMainBinding.root.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottie_verified)
            val bottomSection = holder.itemMainBinding.root.findViewById<LinearLayout>(R.id.layout_bottom_section)

            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.visibility = View.VISIBLE
                bottomSection?.setBackgroundColor(Color.parseColor("#1A4CAF50")) 
                lottieVerified?.visibility = View.VISIBLE
                lottieVerified?.playAnimation()
            } else {
                holder.itemMainBinding.layoutIndicator.visibility = View.INVISIBLE
                bottomSection?.setBackgroundColor(Color.TRANSPARENT)
                lottieVerified?.visibility = View.GONE
                lottieVerified?.cancelAnimation()
            }

            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, true)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                if (isProtected) {
                    holder.itemMainBinding.layoutEdit.visibility = View.GONE
                } else {
                    holder.itemMainBinding.layoutEdit.visibility = View.VISIBLE
                }

                holder.itemMainBinding.layoutShare.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, false)
                }

                holder.itemMainBinding.layoutEdit.setOnClickListener {
                    if (!isProtected) {
                        adapterListener?.onEdit(guid, position, profile)
                    }
                }
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }
    }

    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
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
        coroutineScope.cancel()
    }
}
