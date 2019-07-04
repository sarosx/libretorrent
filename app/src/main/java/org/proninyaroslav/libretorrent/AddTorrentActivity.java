/*
 * Copyright (C) 2016-2018 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package org.proninyaroslav.libretorrent;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import androidx.databinding.DataBindingUtil;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProviders;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import org.proninyaroslav.libretorrent.adapters.AddTorrentPagerAdapter;
import org.proninyaroslav.libretorrent.core.exceptions.DecodeException;
import org.proninyaroslav.libretorrent.core.exceptions.FetchLinkException;
import org.proninyaroslav.libretorrent.core.exceptions.FileAlreadyExistsException;
import org.proninyaroslav.libretorrent.core.exceptions.FreeSpaceException;
import org.proninyaroslav.libretorrent.core.exceptions.NoFilesSelectedException;
import org.proninyaroslav.libretorrent.core.utils.Utils;
import org.proninyaroslav.libretorrent.databinding.ActivityAddTorrentBinding;
import org.proninyaroslav.libretorrent.dialogs.BaseAlertDialog;
import org.proninyaroslav.libretorrent.dialogs.ErrorReportDialog;
import org.proninyaroslav.libretorrent.viewmodel.AddTorrentViewModel;

import java.io.FileNotFoundException;
import java.io.IOException;

/*
 * The dialog for adding torrent. The parent window.
 */

public class AddTorrentActivity extends AppCompatActivity
{
    @SuppressWarnings("unused")
    private static final String TAG = AddTorrentActivity.class.getSimpleName();

    private static final String TAG_PERM_DIALOG_IS_SHOW = "perm_dialog_is_show";

    public static final String TAG_URI = "uri";
    public static final String TAG_ADD_TORRENT_PARAMS = "add_torrent_params";

    private static final String TAG_ERR_REPORT_DIALOG = "io_err_report_dialog";
    private static final String TAG_DECODE_EXCEPT_DIALOG = "decode_except_dialog";
    private static final String TAG_FETCH_EXCEPT_DIALOG = "fetch_except_dialog";
    private static final String TAG_OUT_OF_MEMORY_DIALOG = "out_of_memory_dialog";
    private static final String TAG_ILLEGAL_ARGUMENT_DIALOG = "illegal_argument_dialog";
    private static final String TAG_ADD_ERROR_DIALOG = "add_error_dialog";

    private ActivityAddTorrentBinding binding;
    private AddTorrentViewModel viewModel;
    private AddTorrentPagerAdapter adapter;
    private boolean permDialogIsShow = false;
    private BaseAlertDialog.SharedViewModel dialogViewModel;
    private ErrorReportDialog errReportDialog;
    private CompositeDisposable disposable = new CompositeDisposable();

    @Override
    protected void onStop()
    {
        super.onStop();

        disposable.clear();
    }

    @Override
    public void onStart()
    {
        super.onStart();

        subscribeAlertDialog();
    }

    private void subscribeAlertDialog()
    {
        Disposable d = dialogViewModel.observeEvents().subscribe(this::handleAlertDialogEvent);
        disposable.add(d);
    }

    private void handleAlertDialogEvent(BaseAlertDialog.Event event)
    {
        switch (event.type) {
            case POSITIVE_BUTTON_CLICKED:
                if (event.dialogTag.equals(TAG_ERR_REPORT_DIALOG) && errReportDialog != null) {
                    Dialog dialog = errReportDialog.getDialog();
                    if (dialog != null) {
                        TextInputEditText editText = dialog.findViewById(R.id.comment);
                        Editable e = editText.getText();
                        String comment = (e == null ? null : e.toString());

                        Utils.reportError(viewModel.errorReport, comment);
                        errReportDialog.dismiss();
                    }
                }
                finish();
                break;
            case NEGATIVE_BUTTON_CLICKED:
                if (event.dialogTag.equals(TAG_ERR_REPORT_DIALOG) && errReportDialog != null)
                    errReportDialog.dismiss();
                finish();
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        setTheme(Utils.getAppTheme(getApplicationContext()));
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null)
            permDialogIsShow = savedInstanceState.getBoolean(TAG_PERM_DIALOG_IS_SHOW);

        if (!Utils.checkStoragePermission(getApplicationContext()) && !permDialogIsShow) {
            permDialogIsShow = true;
            startActivity(new Intent(this, RequestPermissions.class));
        }

        binding = DataBindingUtil.setContentView(this, R.layout.activity_add_torrent);
        viewModel = ViewModelProviders.of(this).get(AddTorrentViewModel.class);

        errReportDialog = (ErrorReportDialog)getSupportFragmentManager().findFragmentByTag(TAG_ERR_REPORT_DIALOG);

        dialogViewModel = ViewModelProviders.of(this).get(BaseAlertDialog.SharedViewModel.class);

        initLayout();
        observeDecodeState();
    }

    private void initLayout()
    {
        binding.toolbar.setTitle(R.string.add_torrent_title);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        /* Disable elevation for portrait mode */
        if (!Utils.isTwoPane(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            binding.toolbar.setElevation(0);

        adapter = new AddTorrentPagerAdapter(this, getSupportFragmentManager());
        binding.viewpager.setAdapter(adapter);
        binding.viewpager.setOffscreenPageLimit(AddTorrentPagerAdapter.NUM_FRAGMENTS);
        binding.tabLayout.setupWithViewPager(binding.viewpager);
    }

    private void observeDecodeState()
    {
        viewModel.decodeState.observe(this, (state) -> {
            switch (state.status) {
                case UNKNOWN:
                    Uri uri = getUri();
                    if (uri != null)
                        viewModel.startDecodeTask(uri);
                    break;
                case DECODE_TORRENT_FILE:
                case FETCHING_HTTP:
                case FETCHING_MAGNET:
                    onStartDecode();
                    break;
                case FETCHING_HTTP_COMPLETED:
                case DECODE_TORRENT_COMPLETED:
                case FETCHING_MAGNET_COMPLETED:
                case ERROR:
                    onStopDecode(state.error);
                    break;
            }
        });
    }

    private void onStartDecode()
    {
        binding.progress.setVisibility(View.VISIBLE);
    }

    private void onStopDecode(Throwable e)
    {
        binding.progress.setVisibility(View.GONE);

        if (e != null) {
            handleDecodeException(e);
            return;
        }

        viewModel.makeFileTree();
    }

    private Uri getUri()
    {
        Intent i = getIntent();
        /* Implicit intent with path to torrent file, http or magnet link */
        if (i.getData() != null)
            return i.getData();
        else if (i.getStringExtra(Intent.EXTRA_TEXT) != null)
            return Uri.parse(i.getStringExtra(Intent.EXTRA_TEXT));
        else
            return i.getParcelableExtra(TAG_URI);
    }

    private void addTorrent()
    {
        String name = viewModel.params.getName();
        if (TextUtils.isEmpty(name)) {
            Snackbar.make(binding.coordinatorLayout,
                    R.string.error_empty_name,
                    Snackbar.LENGTH_LONG)
                    .show();
            return;
        }

        try {
            if (viewModel.addTorrent())
                finish();

        } catch (Exception e) {
            if (e instanceof FileAlreadyExistsException) {
                Toast.makeText(getApplication(),
                        R.string.torrent_exist,
                        Toast.LENGTH_SHORT)
                        .show();
                finish();

            } else {
                handleAddException(e);
            }
        }
    }

    private void handleAddException(Throwable e)
    {
        if (e instanceof NoFilesSelectedException) {
            Snackbar.make(binding.coordinatorLayout,
                    R.string.error_no_files_selected,
                    Snackbar.LENGTH_LONG)
                    .show();
            return;
        }

        if (e instanceof FreeSpaceException) {
            Snackbar.make(binding.coordinatorLayout,
                    R.string.error_free_space,
                    Snackbar.LENGTH_LONG)
                    .show();
            return;
        }

        Log.e(TAG, Log.getStackTraceString(e));
        String message;
        if (e instanceof FileNotFoundException)
            message = getApplication().getString(R.string.error_file_not_found_add_torrent);
        else if (e instanceof IOException)
            message = getApplication().getString(R.string.error_io_add_torrent);
        else
            message = getApplication().getString(R.string.error_add_torrent);
        showAddErrorDialog(message);
    }

    private void showAddErrorDialog(String message)
    {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(TAG_ADD_ERROR_DIALOG) == null) {
            BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                    getString(R.string.error),
                    message,
                    0,
                    getString(R.string.ok),
                    null,
                    null,
                    false);

            FragmentTransaction ft = fm.beginTransaction();
            ft.add(errDialog, TAG_ADD_ERROR_DIALOG);
            ft.commitAllowingStateLoss();
        }
    }

    public void handleDecodeException(Throwable e)
    {
        if (e == null)
            return;

        Log.e(TAG, Log.getStackTraceString(e));
        FragmentManager fm = getSupportFragmentManager();

        if (e instanceof DecodeException) {
            if (fm.findFragmentByTag(TAG_DECODE_EXCEPT_DIALOG) == null) {
                BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                        getString(R.string.error),
                        getString(R.string.error_decode_torrent),
                        0,
                        getString(R.string.ok),
                        null,
                        null,
                        false);

                FragmentTransaction ft = fm.beginTransaction();
                ft.add(errDialog, TAG_DECODE_EXCEPT_DIALOG);
                ft.commitAllowingStateLoss();
            }

        } else if (e instanceof FetchLinkException) {
            if (fm.findFragmentByTag(TAG_FETCH_EXCEPT_DIALOG) == null) {
                BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                        getString(R.string.error),
                        getString(R.string.error_fetch_link),
                        0,
                        getString(R.string.ok),
                        null,
                        null,
                        false);

                FragmentTransaction ft = fm.beginTransaction();
                ft.add(errDialog, TAG_FETCH_EXCEPT_DIALOG);
                ft.commitAllowingStateLoss();
            }

        } else if (e instanceof IllegalArgumentException) {
            if (fm.findFragmentByTag(TAG_ILLEGAL_ARGUMENT_DIALOG) == null) {
                BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                        getString(R.string.error),
                        getString(R.string.error_invalid_link_or_path),
                        0,
                        getString(R.string.ok),
                        null,
                        null,
                        false);

                FragmentTransaction ft = fm.beginTransaction();
                ft.add(errDialog, TAG_ILLEGAL_ARGUMENT_DIALOG);
                ft.commitAllowingStateLoss();
            }

        } else if (e instanceof IOException) {
            viewModel.errorReport = e;
            if (fm.findFragmentByTag(TAG_ERR_REPORT_DIALOG) == null) {
                errReportDialog = ErrorReportDialog.newInstance(
                        getString(R.string.error),
                        getString(R.string.error_io_torrent),
                        Log.getStackTraceString(e));

                FragmentTransaction ft = fm.beginTransaction();
                ft.add(errReportDialog, TAG_ERR_REPORT_DIALOG);
                ft.commitAllowingStateLoss();
            }

        } else if (e instanceof OutOfMemoryError) {
            if (fm.findFragmentByTag(TAG_OUT_OF_MEMORY_DIALOG) == null) {
                BaseAlertDialog errDialog = BaseAlertDialog.newInstance(
                        getString(R.string.error),
                        getString(R.string.file_is_too_large_error),
                        0,
                        getString(R.string.ok),
                        null,
                        null,
                        false);

                FragmentTransaction ft = fm.beginTransaction();
                ft.add(errDialog, TAG_OUT_OF_MEMORY_DIALOG);
                ft.commitAllowingStateLoss();
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.add_torrent, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.add_torrent_dialog_add_menu:
                addTorrent();
                break;
        }

        return true;
    }

    @Override
    public void finish()
    {
        viewModel.finish();

        super.finish();
    }

    @Override
    public void onBackPressed()
    {
        finish();
    }
}
