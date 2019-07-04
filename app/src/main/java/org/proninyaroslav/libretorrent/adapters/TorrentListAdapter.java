/*
 * Copyright (C) 2016-2019 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.adapters;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.selection.ItemDetailsLookup;
import androidx.recyclerview.selection.ItemKeyProvider;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.TorrentStateCode;
import org.proninyaroslav.libretorrent.core.utils.Utils;
import org.proninyaroslav.libretorrent.databinding.ItemTorrentListBinding;

import java.util.concurrent.atomic.AtomicReference;

public class TorrentListAdapter extends ListAdapter<TorrentListItem, TorrentListAdapter.ViewHolder>
    implements Selectable<TorrentListItem>
{
    private ClickListener listener;
    private SelectionTracker<TorrentListItem> selectionTracker;
    private AtomicReference<TorrentListItem> currOpenTorrent = new AtomicReference<>();
    private boolean onBind = false;

    public TorrentListAdapter(ClickListener listener)
    {
        super(diffCallback);

        this.listener = listener;
    }

    public void setSelectionTracker(SelectionTracker<TorrentListItem> selectionTracker)
    {
        this.selectionTracker = selectionTracker;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ItemTorrentListBinding binding = DataBindingUtil.inflate(inflater,
                R.layout.item_torrent_list,
                parent,
                false);

        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position)
    {
        onBind = true;

        TorrentListItem item = getItem(position);

        if (selectionTracker != null)
            holder.setSelected(selectionTracker.isSelected(item));

        boolean isOpened = false;
        TorrentListItem currTorrent = currOpenTorrent.get();
        if (currTorrent != null)
            isOpened = item.torrentId.equals(currTorrent.torrentId);

        holder.bind(item, isOpened, listener);

        onBind = false;
    }

    @Override
    public TorrentListItem getItemKey(int position)
    {
        if (position < 0 || position >= getCurrentList().size())
            return null;

        return getItem(position);
    }

    @Override
    public int getItemPosition(TorrentListItem key)
    {
        return getCurrentList().indexOf(key);
    }

    public void markAsOpen(TorrentListItem item)
    {
        TorrentListItem prevItem = currOpenTorrent.getAndSet(item);

        if (onBind)
            return;

        int position = getItemPosition(prevItem);
        if (position >= 0)
            notifyItemChanged(position);

        if (item != null) {
            position = getItemPosition(item);
            if (position >= 0)
                notifyItemChanged(position);
        }
    }

    public TorrentListItem getOpenedItem()
    {
        return currOpenTorrent.get();
    }

    interface ViewHolderWithDetails
    {
        ItemDetails getItemDetails();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder
        implements ViewHolderWithDetails
    {
        private ItemTorrentListBinding binding;
        private AnimatedVectorDrawableCompat playToPauseAnim;
        private AnimatedVectorDrawableCompat pauseToPlayAnim;
        private AnimatedVectorDrawableCompat currAnim;
        /* For selection support */
        private ItemDetails details = new ItemDetails();
        private boolean isSelected;

        ViewHolder(ItemTorrentListBinding binding)
        {
            super(binding.getRoot());

            this.binding = binding;
            Utils.colorizeProgressBar(itemView.getContext(), binding.progress);
            playToPauseAnim = AnimatedVectorDrawableCompat.create(itemView.getContext(), R.drawable.play_to_pause);
            pauseToPlayAnim = AnimatedVectorDrawableCompat.create(itemView.getContext(), R.drawable.pause_to_play);
        }

        void bind(TorrentListItem item, boolean isOpened, ClickListener listener)
        {
            Context context = itemView.getContext();
            details.adapterPosition = getAdapterPosition();
            details.selectionKey = item;

            TypedArray a = context.obtainStyledAttributes(new TypedValue().data, new int[] {
                    R.attr.defaultSelectRect,
                    R.attr.defaultRectRipple
            });
            Drawable d;
            if (isSelected)
                d = a.getDrawable(0);
            else
                d = a.getDrawable(1);
            if (d != null)
                Utils.setBackground(itemView, d);
            a.recycle();

            itemView.setOnClickListener((v) -> {
                /* Skip selecting and deselecting */
                if (isSelected)
                    return;

                if (listener != null)
                    listener.onItemClicked(item);
            });

            setPauseButtonState(item.stateCode == TorrentStateCode.PAUSED);
            binding.pause.setOnClickListener((v) -> {
                if (listener != null)
                    listener.onItemPauseClicked(item);
            });

            binding.name.setText(item.name);
            if (item.stateCode == TorrentStateCode.DOWNLOADING_METADATA) {
                binding.progress.setIndeterminate(true);
            } else {
                binding.progress.setIndeterminate(false);
                binding.progress.setProgress(item.progress);
            }

            String statusString = "";
            switch (item.stateCode) {
                case DOWNLOADING:
                    statusString = context.getString(R.string.torrent_status_downloading);
                    break;
                case SEEDING:
                    statusString = context.getString(R.string.torrent_status_seeding);
                    break;
                case PAUSED:
                    statusString = context.getString(R.string.torrent_status_paused);
                    break;
                case STOPPED:
                    statusString = context.getString(R.string.torrent_status_stopped);
                    break;
                case FINISHED:
                    statusString = context.getString(R.string.torrent_status_finished);
                    break;
                case CHECKING:
                    statusString = context.getString(R.string.torrent_status_checking);
                    break;
                case DOWNLOADING_METADATA:
                    statusString = context.getString(R.string.torrent_status_downloading_metadata);
            }
            binding.status.setText(statusString);

            String counterTemplate = context.getString(R.string.download_counter_ETA_template);
            String totalBytes = Formatter.formatFileSize(context, item.totalBytes);
            String receivedBytes;
            String ETA;
            if (item.ETA == -1)
                ETA = "\u2022 " + Utils.INFINITY_SYMBOL;
            else if (item.ETA == 0)
                ETA = "";
            else
                ETA = "\u2022 " + DateUtils.formatElapsedTime(item.ETA);

            if (item.progress == 100)
                receivedBytes = totalBytes;
            else
                receivedBytes = Formatter.formatFileSize(context, item.receivedBytes);

            binding.downloadCounter.setText(String.format(counterTemplate, receivedBytes, totalBytes,
                    (item.totalBytes == 0 ? 0 : item.progress), ETA));

            String speedTemplate = context.getString(R.string.download_upload_speed_template);
            String downloadSpeed = Formatter.formatFileSize(context, item.downloadSpeed);
            String uploadSpeed = Formatter.formatFileSize(context, item.uploadSpeed);
            binding.downloadUploadSpeed.setText(String.format(speedTemplate, downloadSpeed, uploadSpeed));

            String peersTemplate = context.getString(R.string.peers_template);
            binding.peers.setText(String.format(peersTemplate, item.peers, item.totalPeers));
            setPauseButtonState(item.stateCode == TorrentStateCode.PAUSED);

            if (item.error != null) {
                binding.error.setVisibility(View.VISIBLE);
                String errorTemplate = context.getString(R.string.error_template);
                binding.error.setText(String.format(errorTemplate, item.error));
            } else {
                binding.error.setVisibility(View.GONE);
            }

            if (isOpened) {
                if (!isSelected) {
                    a = context.obtainStyledAttributes(new TypedValue().data, new int[]{ R.attr.curOpenTorrentIndicator });
                    d = a.getDrawable(0);
                    if (d != null)
                        Utils.setBackground(itemView, d);
                    a.recycle();
                }
                d = ContextCompat.getDrawable(context, R.color.accent);
                if (d != null)
                    Utils.setBackground(binding.indicatorCurOpenTorrent, d);
            } else {
                d = ContextCompat.getDrawable(context, android.R.color.transparent);
                if (d != null)
                    Utils.setBackground(binding.indicatorCurOpenTorrent, d);
            }
        }

        private void setSelected(boolean isSelected)
        {
            this.isSelected = isSelected;
        }

        @Override
        public ItemDetails getItemDetails()
        {
            return details;
        }

        void setPauseButtonState(boolean isPause)
        {
            AnimatedVectorDrawableCompat prevAnim = currAnim;
            currAnim = (isPause ? pauseToPlayAnim : playToPauseAnim);
            binding.pause.setImageDrawable(currAnim);
            if (currAnim != prevAnim)
                currAnim.start();
        }
    }

    public interface ClickListener
    {
        void onItemClicked(@NonNull TorrentListItem item);

        void onItemPauseClicked(@NonNull TorrentListItem item);
    }

    public static final DiffUtil.ItemCallback<TorrentListItem> diffCallback = new DiffUtil.ItemCallback<TorrentListItem>()
    {
        @Override
        public boolean areContentsTheSame(@NonNull TorrentListItem oldItem,
                                          @NonNull TorrentListItem newItem)
        {
            return oldItem.equalsContent(newItem);
        }

        @Override
        public boolean areItemsTheSame(@NonNull TorrentListItem oldItem,
                                       @NonNull TorrentListItem newItem)
        {
            return oldItem.equals(newItem);
        }
    };

    /*
     * Selection support stuff
     */

    public static final class KeyProvider extends ItemKeyProvider<TorrentListItem>
    {
        Selectable<TorrentListItem> selectable;

        public KeyProvider(Selectable<TorrentListItem> selectable)
        {
            super(SCOPE_MAPPED);

            this.selectable = selectable;
        }

        @Nullable
        @Override
        public TorrentListItem getKey(int position)
        {
            return selectable.getItemKey(position);
        }

        @Override
        public int getPosition(@NonNull TorrentListItem key)
        {
            return selectable.getItemPosition(key);
        }
    }

    public static final class ItemDetails extends ItemDetailsLookup.ItemDetails<TorrentListItem>
    {
        public TorrentListItem selectionKey;
        public int adapterPosition;

        @Nullable
        @Override
        public TorrentListItem getSelectionKey()
        {
            return selectionKey;
        }

        @Override
        public int getPosition()
        {
            return adapterPosition;
        }
    }

    public static class ItemLookup extends ItemDetailsLookup<TorrentListItem>
    {
        private final RecyclerView recyclerView;

        public ItemLookup(RecyclerView recyclerView)
        {
            this.recyclerView = recyclerView;
        }

        @Nullable
        @Override
        public ItemDetails<TorrentListItem> getItemDetails(@NonNull MotionEvent e)
        {
            View view = recyclerView.findChildViewUnder(e.getX(), e.getY());
            if (view != null) {
                RecyclerView.ViewHolder viewHolder = recyclerView.getChildViewHolder(view);
                if (viewHolder instanceof TorrentListAdapter.ViewHolder)
                    return ((ViewHolder)viewHolder).getItemDetails();
            }

            return null;
        }
    }
}
