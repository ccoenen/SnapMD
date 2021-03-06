package de.claudiuscoenen.snapmd.padselection;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.claudiuscoenen.snapmd.R;
import de.claudiuscoenen.snapmd.SnapMdApplication;
import de.claudiuscoenen.snapmd.model.Pad;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;


public class PadSelectionFragment extends Fragment {

	@BindView(R.id.list_pads)
	RecyclerView padList;

	private final CompositeDisposable loadingOperations = new CompositeDisposable();
	private Listener listener;


	public static PadSelectionFragment newInstance() {
		return new PadSelectionFragment();
	}


	public PadSelectionFragment() {
		// Required empty public constructor
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_pad_selection, container, false);
		ButterKnife.bind(this, view);

		PadListAdapter adapter = new PadListAdapter(listener::onPadSelected);
		padList.setLayoutManager(new LinearLayoutManager(getContext()));
		padList.setAdapter(adapter);

		SnapMdApplication app = (SnapMdApplication) getActivity().getApplication();

		// TODO: show loading indicator
		Disposable disposable = app.getApi().getPads()
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(adapter::setPads, this::onLoadPadsError);
		loadingOperations.add(disposable);

		return view;
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		if (context instanceof Listener) {
			listener = (Listener) context;
		} else {
			throw new RuntimeException(context.toString() + " must implement Listener");
		}
	}

	@Override
	public void onDetach() {
		super.onDetach();
		listener = null;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		loadingOperations.dispose();
	}

	private void onLoadPadsError(Throwable t) {
		Toast.makeText(getContext(), t.getMessage(), Toast.LENGTH_LONG).show();
	}

	/**
	 * This interface must be implemented by activities that contain this
	 * fragment to allow an interaction in this fragment to be communicated
	 * to the activity and potentially other fragments contained in that
	 * activity.
	 */
	public interface Listener {
		void onPadSelected(Pad pad);
	}
}
