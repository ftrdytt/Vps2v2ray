<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000"
    android:layoutDirection="rtl">

    <FrameLayout
        android:id="@+id/story_content_container"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        android:layout_marginTop="75dp"
        android:layout_marginBottom="85dp">

        <VideoView
            android:id="@+id/vv_story_video"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:layout_gravity="center"
            android:visibility="gone" />

        <ImageView
            android:id="@+id/iv_story_image"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:layout_gravity="center"
            android:scaleType="fitCenter"
            android:visibility="gone"/>

        <TextView
            android:id="@+id/tv_story_text"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:padding="24dp"
            android:textColor="#FFFFFF"
            android:textSize="24sp"
            android:textStyle="bold"
            android:shadowColor="#000000"
            android:shadowDx="1"
            android:shadowDy="1"
            android:shadowRadius="5"
            android:visibility="gone"/>
    </FrameLayout>

    <View
        android:id="@+id/view_touch_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <FrameLayout
        android:id="@+id/reaction_animation_layer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:elevation="10dp"
        android:clipChildren="false"/>

    <View
        android:layout_width="match_parent"
        android:layout_height="120dp"
        android:background="@drawable/shadow_gradient_top"
        app:layout_constraintTop_toTopOf="parent"/>

    <LinearLayout
        android:id="@+id/layout_progress_bars"
        android:layout_width="match_parent"
        android:layout_height="3dp"
        android:orientation="horizontal"
        android:layoutDirection="rtl" 
        android:layout_marginTop="12dp"
        android:layout_marginStart="8dp"
        android:layout_marginEnd="8dp"
        app:layout_constraintTop_toTopOf="parent"/>

    <LinearLayout
        android:id="@+id/layout_top_bar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:layoutDirection="rtl"
        android:padding="16dp"
        app:layout_constraintTop_toBottomOf="@id/layout_progress_bars">

        <androidx.cardview.widget.CardView
            android:layout_width="42dp"
            android:layout_height="42dp"
            app:cardCornerRadius="21dp"
            app:cardBackgroundColor="#33FFFFFF"
            app:cardElevation="0dp">
            <ImageView
                android:id="@+id/iv_story_avatar"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:scaleType="centerCrop"/>
        </androidx.cardview.widget.CardView>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginStart="12dp">
            
            <TextView
                android:id="@+id/tv_story_username"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFF"
                android:textSize="16sp"
                android:textStyle="bold"
                android:shadowColor="#000000"
                android:shadowRadius="4"/>

            <TextView
                android:id="@+id/tv_story_time"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#E0E0E0"
                android:textSize="12sp"
                android:shadowColor="#000000"
                android:shadowRadius="4"/>
        </LinearLayout>

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_follow"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:layout_marginStart="12dp"
            android:text="متابعة"
            android:textSize="12sp"
            app:cornerRadius="18dp"
            app:backgroundTint="#2196F3"
            android:visibility="gone"/>

        <View
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_weight="1"/>

        <ImageView
            android:id="@+id/btn_story_options"
            android:layout_width="36dp"
            android:layout_height="36dp"
            android:padding="6dp"
            android:layout_marginEnd="8dp"
            android:src="@android:drawable/ic_menu_more"
            android:visibility="gone"
            android:background="@drawable/bg_rounded_dark"
            app:tint="#FFFFFF"/>

        <ImageView
            android:id="@+id/btn_close_story"
            android:layout_width="36dp"
            android:layout_height="36dp"
            android:padding="6dp"
            android:src="@android:drawable/ic_menu_close_clear_cancel"
            android:background="@drawable/bg_rounded_dark"
            app:tint="#FFFFFF"/>
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="180dp"
        android:background="@drawable/shadow_gradient_bottom"
        app:layout_constraintBottom_toBottomOf="parent"/>

    <LinearLayout
        android:id="@+id/layout_footer"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layoutDirection="rtl"
        android:gravity="center_vertical"
        android:paddingHorizontal="12dp"
        android:paddingVertical="16dp"
        android:layout_marginBottom="6dp"
        app:layout_constraintBottom_toBottomOf="parent">

        <LinearLayout
            android:id="@+id/layout_story_views_container"
            android:layout_width="wrap_content"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:background="@drawable/bg_rounded_dark"
            android:paddingHorizontal="16dp"
            android:layout_marginEnd="10dp">
            
            <ImageView
                android:layout_width="20dp"
                android:layout_height="20dp"
                android:src="@android:drawable/ic_menu_view"
                app:tint="#FFFFFF" />

            <TextView
                android:id="@+id/tv_story_views"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:textColor="#FFFFFF"
                android:textSize="15sp"
                android:text="0"
                android:textStyle="bold" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:background="@drawable/bg_rounded_dark"
            android:paddingHorizontal="14dp">
            
            <TextView
                android:id="@+id/btn_react_heart"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="❤️"
                android:textSize="26sp"
                android:padding="2dp"/>
            
            <TextView
                android:id="@+id/btn_react_fire"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🔥"
                android:textSize="26sp"
                android:padding="2dp"
                android:layout_marginStart="14dp"/>
            
            <TextView
                android:id="@+id/btn_react_laugh"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="😂"
                android:textSize="26sp"
                android:padding="2dp"
                android:layout_marginStart="14dp"/>
        </LinearLayout>

        <View
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_weight="1"/>

        <LinearLayout
            android:id="@+id/btn_open_comments"
            android:layout_width="wrap_content"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:background="@drawable/bg_rounded_dark"
            android:paddingHorizontal="18dp">
            
            <TextView
                android:id="@+id/tv_comments_count"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="0"
                android:textSize="15sp"
                android:textColor="#FFFFFF"
                android:textStyle="bold"
                android:layout_marginEnd="8dp"/>
            <ImageView
                android:layout_width="22dp"
                android:layout_height="22dp"
                android:src="@android:drawable/ic_menu_send"
                app:tint="#FFFFFF"/>
        </LinearLayout>
    </LinearLayout>

    <ProgressBar
        android:id="@+id/pb_loading"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:indeterminateTint="#FF9800"
        android:visibility="gone"/>

</androidx.constraintlayout.widget.ConstraintLayout>
 