package com.appatalks.lcars_translator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BluetoothSelectorSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(
            devices: List<BluetoothDevice>,
            onSelected: (BluetoothDevice) -> Unit
        ): BluetoothSelectorSheet {
            return BluetoothSelectorSheet().apply {
                this.devices = devices
                this.onSelected = onSelected
            }
        }
    }

    private var devices: List<BluetoothDevice> = emptyList()
    var onSelected: ((BluetoothDevice) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_bluetooth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rvBluetoothDevices)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = BtDeviceAdapter(devices) { device ->
            onSelected?.invoke(device)
            dismiss()
        }
    }
}

// ── BT Device RecyclerView Adapter ─────────────────────────────────────────

private class BtDeviceAdapter(
    private val items: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BtDeviceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_bluetooth_device, parent, false))

    override fun getItemCount() = items.size

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val device = items[position]
        holder.tvName.text = device.name ?: "Unknown Device"
        holder.tvAddress.text = device.address
        holder.itemView.setOnClickListener { onClick(device) }
    }
}


